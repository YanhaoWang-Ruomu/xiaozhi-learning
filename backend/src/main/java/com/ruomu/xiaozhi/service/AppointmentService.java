package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.config.AppointmentStorageInitializer;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.AppointmentSessionSelection;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.entity.AppointmentEntity;
import com.ruomu.xiaozhi.mapper.AppointmentMapper;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Service
public class AppointmentService {

    private static final ZoneId ZONE = AppointmentBookingPolicy.ZONE;

    private final AppointmentMapper mapper;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AppointmentSessionBookingService sessionBooking;

    public AppointmentService(
            AppointmentMapper mapper,
            AppointmentStorageInitializer initializer,
            DataSource dataSource) {

        initializer.initialize();
        this.mapper = mapper;
        this.sessionBooking = new AppointmentSessionBookingService(dataSource);
        this.jdbc = new JdbcTemplate(dataSource);
        this.tx = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );

        tx.setIsolationLevel(
                TransactionDefinition.ISOLATION_READ_COMMITTED
        );
        tx.setPropagationBehavior(
                TransactionDefinition.PROPAGATION_REQUIRES_NEW
        );
        tx.setTimeout(15);

        // 缺少新表时直接阻止启动，避免到确认预约时才发现。
        jdbc.queryForObject(
                "SELECT COUNT(*) FROM demo_appointment_attempts WHERE 1 = 0",
                Long.class
        );
    }

    public AppointmentResponse create(
            CreateAppointmentRequest request,
            String key) {

        return createAt(request, key, Instant.now(), null);
    }

    AppointmentResponse createForConfirmedDraft(
            CreateAppointmentRequest request,
            String draftId,
            Instant acceptedAt) {

        return createAt(request, "draft:" + draftId, acceptedAt, null);
    }

    AppointmentResponse createForConfirmedDraft(CreateAppointmentRequest request,
                                                String draftId, Instant acceptedAt, AppointmentSessionSelection expected) {
        return createAt(request, "draft:" + draftId, acceptedAt, expected);
    }

    AppointmentSessionSelection sessionForDraft(CreateAppointmentRequest request) {
        return sessionBooking.forDraft(request);
    }

    AppointmentResponse findExistingForDraft(
            CreateAppointmentRequest request,
            String draftId) {

        String key = requireRequestId("draft:" + draftId);
        AppointmentEntity existing = mapper.selectById(appointmentId(key));

        return existing == null
                ? null
                : reuseExisting(existing, key, request);
    }

    void validateForConfirmation(
            CreateAppointmentRequest request,
            Instant acceptedAt) {

        AppointmentBookingPolicy.validateConfirmation(
                request,
                acceptedAt
        );
    }

    private AppointmentResponse createAt(
            CreateAppointmentRequest request,
            String key,
            Instant acceptedAt, AppointmentSessionSelection expected) {

        if (request == null) {
            throw badRequest("请求内容不能为空");
        }

        String sessionId = sessionBooking.normalizeId(request.sessionId());
        String requestId = requireRequestId(key);
        String id = appointmentId(requestId);

        AppointmentEntity existing = mapper.selectById(id);
        if (existing != null) {
            return reuseExisting(existing, requestId, request);
        }

        if (request.visitDate() == null) {
            throw badRequest("请提供预约日期");
        }

        String hospital = normalize(request.hospitalId());
        String department = normalize(request.department());

        if (hospital.length() > 64 || department.length() > 64) {
            throw badRequest("医院编号和科室分别不能超过64个字符");
        }

        Decision decision = Objects.requireNonNull(
                tx.execute(transaction -> {
                    // 先锁请求编号，再锁排班。
                    // 相同请求只能得到同一个最终结果。
                    jdbc.update("""
                            INSERT INTO demo_appointment_attempts
                                (request_id, hospital_id, department,
                                 visit_date, status)
                            VALUES (?, ?, ?, ?, 'PROCESSING')
                            ON DUPLICATE KEY UPDATE
                                request_id = demo_appointment_attempts.request_id
                            """,
                            requestId,
                            hospital,
                            department,
                            Date.valueOf(request.visitDate())
                    );

                    Attempt attempt = jdbc.queryForObject("""
                            SELECT hospital_id, department, visit_date,
                                   status, reason
                            FROM demo_appointment_attempts
                            WHERE request_id = ?
                            FOR UPDATE
                            """,
                            (rs, row) -> new Attempt(
                                    rs.getString("hospital_id"),
                                    rs.getString("department"),
                                    rs.getDate("visit_date").toLocalDate(),
                                    rs.getString("status"),
                                    rs.getString("reason")
                            ),
                            requestId
                    );

                    if (attempt == null
                            || !hospital.equals(attempt.hospitalId())
                            || !department.equals(attempt.department())
                            || !request.visitDate().equals(attempt.visitDate())) {

                        throw conflict(
                                "同一个 Idempotency-Key 不能用于不同的预约内容"
                        );
                    }

                    sessionBooking.bindTarget(requestId, sessionId);

                    AppointmentEntity saved = mapper.selectById(id);

                    if (saved != null) {
                        AppointmentResponse response = reuseExisting(
                                saved,
                                requestId,
                                request
                        );

                        finishAttempt(requestId, "CREATED", "");
                        return new Decision(response, null);
                    }

                    if ("REJECTED".equals(attempt.status())) {
                        return new Decision(null, attempt.reason());
                    }

                    if (!"PROCESSING".equals(attempt.status())) {
                        throw new IllegalStateException(
                                "确认结果与预约记录不一致，请检查数据库"
                        );
                    }

                    validateForConfirmation(request, acceptedAt);

                    Integer capacity = mapper.lockCapacity(
                            hospital,
                            department,
                            request.visitDate()
                    );

                    if (capacity == null) {
                        return reject(
                                requestId,
                                "该日期尚未配置演示排班；本次确认已拒绝，"
                                        + "请重新选择日期并新建草稿。"
                        );
                    }

                    long activeCount = mapper.countActive(
                            hospital,
                            department,
                            request.visitDate()
                    );

                    if (activeCount >= capacity) {
                        return reject(
                                requestId,
                                "该日期演示名额已满；本次确认已拒绝，"
                                        + "如需再次预约请新建草稿。"
                        );
                    }

                    // 同一日期总容量和场次容量同时生效，旧预约仍计入每日总量。
                    var sessionDecision = sessionBooking.lockAndCheck(request, expected);
                    if (sessionDecision.rejection() != null) {
                        return reject(requestId, sessionDecision.rejection());
                    }

                    AppointmentEntity entity = new AppointmentEntity();
                    entity.setAppointmentId(id);
                    entity.setRequestId(requestId);
                    entity.setStatus("DEMO_CREATED");
                    entity.setHospitalId(hospital);
                    entity.setDepartment(department);
                    entity.setVisitDate(request.visitDate());
                    entity.setTimeZone(ZONE.getId());
                    entity.setAcceptedAt(acceptedAt.toString());
                    entity.setCreatedAt(Instant.now().toString());

                    if (mapper.insert(entity) != 1) {
                        throw new IllegalStateException("预约写入失败");
                    }

                    // 预约、场次快照和确认结果必须在同一个MySQL事务内提交。
                    sessionBooking.save(id, sessionDecision.selection());
                    finishAttempt(requestId, "CREATED", "");
                    return new Decision(toResponse(entity), null);
                })
        );

        // 在事务成功提交后才抛业务拒绝，
        // 确保拒绝结果不会被回滚。
        if (decision.rejection() != null) {
            throw new AppointmentRejectedException(
                    decision.rejection()
            );
        }

        return decision.response();
    }

    private Decision reject(String key, String reason) {
        finishAttempt(key, "REJECTED", reason);
        return new Decision(null, reason);
    }

    private void finishAttempt(
            String key,
            String status,
            String reason) {

        jdbc.update(
                "UPDATE demo_appointment_attempts "
                        + "SET status = ?, reason = ? "
                        + "WHERE request_id = ?",
                status,
                reason,
                key
        );
    }

    public AppointmentResponse findById(String appointmentId) {
        AppointmentEntity entity = mapper.selectById(
                requireAppointmentId(appointmentId)
        );

        if (entity == null) {
            throw notFound();
        }

        return toResponse(entity);
    }

    public AppointmentResponse cancel(
            String appointmentId,
            boolean confirmed) {

        if (!confirmed) {
            throw badRequest("请明确确认取消当前演示预约");
        }

        String id = requireAppointmentId(appointmentId);
        AppointmentEntity original = mapper.selectById(id);

        if (original == null) {
            throw notFound();
        }

        return Objects.requireNonNull(
                tx.execute(transaction -> {
                    // 与创建使用同一排班锁。
                    // 历史预约即使没有排班也允许取消。
                    mapper.lockCapacity(
                            original.getHospitalId(),
                            original.getDepartment(),
                            original.getVisitDate()
                    );

                    mapper.cancelIfActive(
                            id,
                            Instant.now().toString()
                    );

                    AppointmentEntity saved = mapper.selectById(id);

                    if (saved == null) {
                        throw notFound();
                    }

                    if (!"DEMO_CANCELLED".equals(saved.getStatus())) {
                        throw conflict("当前预约状态不允许取消");
                    }

                    // 有效预约数自动少一条，不另外累加名额。
                    // 重复取消不会多返名额。
                    return toResponse(saved);
                })
        );
    }

    private AppointmentResponse reuseExisting(
            AppointmentEntity entity,
            String key,
            CreateAppointmentRequest request) {

        if (request == null
                || !Objects.equals(entity.getRequestId(), key)
                || !Objects.equals(
                entity.getHospitalId(),
                normalize(request.hospitalId())
        )
                || !Objects.equals(
                entity.getDepartment(),
                normalize(request.department())
        )
                || !Objects.equals(
                entity.getVisitDate(),
                request.visitDate()
        )) {

            throw conflict(
                    "同一个 Idempotency-Key 不能用于不同的预约内容"
            );
        }

        var savedSession = sessionBooking.find(entity.getAppointmentId());
        String savedSessionId = savedSession == null ? null : savedSession.sessionId();
        if (!Objects.equals(savedSessionId, sessionBooking.normalizeId(request.sessionId()))) {
            throw conflict("同一个 Idempotency-Key 不能用于不同的预约场次");
        }
        return toResponse(entity);
    }

    private String requireRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("Idempotency-Key 不能为空");
        }

        String key = value.strip();

        if (key.length() > 128) {
            throw badRequest("Idempotency-Key 不能超过128个字符");
        }

        return key;
    }

    private String requireAppointmentId(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("预约编号不能为空");
        }

        return value.strip();
    }

    private String appointmentId(String key) {
        return "DEMO-" + UUID.nameUUIDFromBytes(
                ("demo-appointment:" + key)
                        .getBytes(StandardCharsets.UTF_8)
        );
    }

    private String normalize(String value) {
        return value == null ? "" : value.strip();
    }

    private ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                message
        );
    }

    private ResponseStatusException conflict(String message) {
        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "未找到该演示预约记录"
        );
    }

    private AppointmentResponse toResponse(AppointmentEntity entity) {
        return new AppointmentResponse(
                entity.getAppointmentId(),
                entity.getStatus(),
                entity.getHospitalId(),
                entity.getDepartment(),
                entity.getVisitDate(),
                entity.getTimeZone(),
                "DEMO_CANCELLED".equals(entity.getStatus())
                        ? "本地演示预约已取消，记录保留；"
                        + "不涉及真实医院退号或退款。"
                        : "仅创建本地演示预约记录，"
                        + "不代表真实医院挂号成功。",
                entity.getCancelledAt(),
                sessionBooking.find(entity.getAppointmentId())
        );
    }

    private record Attempt(
            String hospitalId,
            String department,
            LocalDate visitDate,
            String status,
            String reason
    ) {
    }

    private record Decision(
            AppointmentResponse response,
            String rejection
    ) {
    }
}