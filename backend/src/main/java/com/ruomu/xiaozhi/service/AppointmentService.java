package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.config.AppointmentStorageInitializer;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import com.ruomu.xiaozhi.entity.AppointmentEntity;
import com.ruomu.xiaozhi.mapper.AppointmentMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

@Service
public class AppointmentService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final AppointmentMapper mapper;

    public AppointmentService(
            AppointmentMapper mapper,
            AppointmentStorageInitializer initializer) {

        initializer.initialize();
        this.mapper = mapper;
    }

    public AppointmentResponse create(
            CreateAppointmentRequest request,
            String idempotencyKey) {

        return createAt(request, idempotencyKey, Instant.now());
    }

    AppointmentResponse createForConfirmedDraft(
            CreateAppointmentRequest request,
            String draftId,
            Instant confirmationStartedAt) {

        return createAt(
                request,
                "draft:" + draftId,
                confirmationStartedAt
        );
    }

    AppointmentResponse findExistingForDraft(
            CreateAppointmentRequest request,
            String draftId) {

        String requestId = requireRequestId("draft:" + draftId);

        AppointmentEntity existing = mapper.selectById(
                appointmentId(requestId)
        );

        return existing == null
                ? null
                : reuseExisting(existing, requestId, request);
    }

    void validateForConfirmation(
            CreateAppointmentRequest request,
            Instant acceptedAt) {

        if (request == null) {
            throw badRequest("请求内容不能为空");
        }

        if (!"DEMO001".equals(normalize(request.hospitalId()))) {
            throw badRequest("当前仅支持演示医院 DEMO001");
        }

        if (!"内科".equals(normalize(request.department()))) {
            throw badRequest("当前演示仅支持内科");
        }

        LocalDate today = acceptedAt.atZone(ZONE).toLocalDate();
        LocalDate date = request.visitDate();

        if (date == null
                || !date.isAfter(today)
                || date.isAfter(today.plusDays(3))) {

            throw badRequest(
                    "日期必须为上海时区的明天至未来第3天"
            );
        }
    }

    private AppointmentResponse createAt(
            CreateAppointmentRequest request,
            String idempotencyKey,
            Instant acceptedAt) {

        if (request == null) {
            throw badRequest("请求内容不能为空");
        }

        String requestId = requireRequestId(idempotencyKey);
        String id = appointmentId(requestId);

        AppointmentEntity existing = mapper.selectById(id);

        // 先查原记录，保证跨天重试仍使用原结果，包括已取消的结果。
        if (existing != null) {
            return reuseExisting(existing, requestId, request);
        }

        validateForConfirmation(request, acceptedAt);

        AppointmentEntity entity = new AppointmentEntity();
        entity.setAppointmentId(id);
        entity.setRequestId(requestId);
        entity.setStatus("DEMO_CREATED");
        entity.setHospitalId(normalize(request.hospitalId()));
        entity.setDepartment(normalize(request.department()));
        entity.setVisitDate(request.visitDate());
        entity.setTimeZone(ZONE.getId());
        entity.setAcceptedAt(acceptedAt.toString());
        entity.setCreatedAt(Instant.now().toString());

        try {
            mapper.insert(entity);

        } catch (DuplicateKeyException exception) {
            AppointmentEntity saved = mapper.selectById(id);

            if (saved == null) {
                throw exception;
            }

            return reuseExisting(saved, requestId, request);
        }

        return findById(id);
    }

    public AppointmentResponse findById(String appointmentId) {
        String id = requireAppointmentId(appointmentId);

        AppointmentEntity entity = mapper.selectById(id);

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

        // SQL 条件更新保证只有首次取消会写入时间。
        mapper.cancelIfActive(id, Instant.now().toString());

        AppointmentEntity existing = mapper.selectById(id);

        if (existing == null) {
            throw notFound();
        }

        if ("DEMO_CANCELLED".equals(existing.getStatus())) {
            return toResponse(existing);
        }

        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "当前预约状态不允许取消，请重新查询"
        );
    }

    private AppointmentResponse reuseExisting(
            AppointmentEntity entity,
            String requestId,
            CreateAppointmentRequest request) {

        if (request == null
                || !Objects.equals(
                entity.getRequestId(),
                requestId
        )
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

            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "同一个 Idempotency-Key 不能用于不同的预约内容"
            );
        }

        return toResponse(entity);
    }

    private String requireRequestId(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("Idempotency-Key 不能为空");
        }

        String id = value.strip();

        if (id.length() > 128) {
            throw badRequest("Idempotency-Key 不能超过128个字符");
        }

        return id;
    }

    private String requireAppointmentId(String value) {
        if (value == null || value.isBlank()) {
            throw badRequest("预约编号不能为空");
        }

        return value.strip();
    }

    private String appointmentId(String requestId) {
        return "DEMO-" + UUID.nameUUIDFromBytes(
                ("demo-appointment:" + requestId)
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
                        ? "本地演示预约已取消，记录保留；不涉及真实医院退号或退款。"
                        : "仅创建本地演示预约记录，不代表真实医院挂号成功。",
                entity.getCancelledAt()
        );
    }
}