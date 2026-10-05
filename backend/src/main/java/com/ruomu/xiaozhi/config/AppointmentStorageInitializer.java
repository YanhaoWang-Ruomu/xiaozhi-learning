package com.ruomu.xiaozhi.config;

import com.ruomu.xiaozhi.entity.AppointmentEntity;
import com.ruomu.xiaozhi.mapper.AppointmentMapper;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Sorts.ascending;

@Component
public class AppointmentStorageInitializer {

    private static final Logger log =
            LoggerFactory.getLogger(AppointmentStorageInitializer.class);

    private static final String KEY = "mongo-demo-appointments-v1";

    private final AppointmentMapper mapper;
    private final MongoTemplate mongo;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final Environment environment;

    private boolean ready;

    public AppointmentStorageInitializer(
            AppointmentMapper mapper,
            MongoTemplate mongo,
            DataSource dataSource,
            Environment environment) {

        this.mapper = mapper;
        this.mongo = mongo;
        this.jdbc = new JdbcTemplate(dataSource);
        this.transaction = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource)
        );
        this.environment = environment;
    }

    public synchronized void initialize() {
        if (ready) {
            return;
        }

        String database = jdbc.queryForObject(
                "SELECT DATABASE()",
                String.class
        );

        if (!"xiaozhi_learning".equals(database)) {
            throw new IllegalStateException(
                    "MySQL 数据库必须为 xiaozhi_learning"
            );
        }

        boolean importing = environment.getProperty(
                "xiaozhi.appointment.import-from-mongo",
                Boolean.class,
                false
        );

        if (importing) {
            String webType = environment.getProperty(
                    "spring.main.web-application-type"
            );

            if (!"none".equalsIgnoreCase(webType)) {
                throw new IllegalStateException(
                        "迁移必须使用 --spring.main.web-application-type=none"
                );
            }

            transaction.executeWithoutResult(ignored -> importOnce());

            Long importedCount = jdbc.queryForObject(
                    """
                    SELECT imported_count
                    FROM app_data_migrations
                    WHERE migration_key = ?
                    """,
                    Long.class,
                    KEY
            );

            Long legacyCount = jdbc.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM demo_appointments
                    WHERE request_id IS NULL
                    """,
                    Long.class
            );

            log.info(
                    "APPOINTMENT_IMPORT_READY importedCount={} legacyWithoutRequestId={}",
                    importedCount,
                    legacyCount
            );
        }

        String status = jdbc.queryForObject(
                """
                SELECT status
                FROM app_data_migrations
                WHERE migration_key = ?
                """,
                String.class,
                KEY
        );

        if (!"COMPLETED".equals(status)) {
            throw new IllegalStateException(
                    "预约尚未迁移，请先按文档执行离线迁移"
            );
        }

        ready = true;
        log.info("MYSQL_APPOINTMENT_STORAGE_READY");
    }

    private void importOnce() {
        String status = jdbc.queryForObject(
                """
                SELECT status
                FROM app_data_migrations
                WHERE migration_key = ?
                FOR UPDATE
                """,
                String.class,
                KEY
        );

        if ("COMPLETED".equals(status)) {
            log.info(
                    "APPOINTMENT_IMPORT_ALREADY_COMPLETED：不再覆盖 MySQL 中的数据"
            );
            return;
        }

        if (!"PENDING".equals(status)) {
            throw new IllegalStateException("迁移状态异常");
        }

        if (!"xiaozhi_learning".equals(mongo.getDb().getName())) {
            throw new IllegalStateException(
                    "MongoDB 数据库必须为 xiaozhi_learning"
            );
        }

        if (mapper.selectCount(null) != 0L) {
            throw new IllegalStateException(
                    "首次迁移要求 MySQL 预约表为空，请先核对已有数据；不要直接删表"
            );
        }

        List<Document> source = readSource();

        for (Document document : source) {
            AppointmentEntity entity = convert(document);

            mapper.insert(entity);

            AppointmentEntity saved = mapper.selectById(
                    entity.getAppointmentId()
            );

            if (saved == null
                    || !entity.snapshot().equals(saved.snapshot())) {

                throw new IllegalStateException(
                        "迁移逐字段核对失败：" + entity.getAppointmentId()
                );
            }
        }

        if (mapper.selectCount(null) != source.size()
                || !source.equals(readSource())) {

            throw new IllegalStateException(
                    "迁移数量不一致或 MongoDB 在迁移期间发生变化；本次导入回滚"
            );
        }

        jdbc.update(
                """
                UPDATE app_data_migrations
                SET status = 'COMPLETED',
                    imported_count = ?,
                    completed_at = ?
                WHERE migration_key = ?
                """,
                source.size(),
                Instant.now().toString(),
                KEY
        );

        // 成功日志在事务提交之后由 initialize 输出。
    }

    private List<Document> readSource() {
        return mongo.getCollection("demo_appointments")
                .find()
                .sort(ascending("_id"))
                .into(new ArrayList<>());
    }

    private AppointmentEntity convert(Document document) {
        AppointmentEntity entity = new AppointmentEntity();

        entity.setAppointmentId(required(document, "_id"));

        entity.setRequestId(
                readRequestId(document, entity.getAppointmentId())
        );

        entity.setStatus(required(document, "status"));
        entity.setHospitalId(required(document, "hospitalId"));
        entity.setDepartment(required(document, "department"));

        entity.setVisitDate(
                LocalDate.parse(required(document, "visitDate"))
        );

        entity.setTimeZone(required(document, "timeZone"));
        entity.setAcceptedAt(document.getString("acceptedAt"));
        entity.setCreatedAt(required(document, "createdAt"));
        entity.setCancelledAt(document.getString("cancelledAt"));

        if (!"Asia/Shanghai".equals(entity.getTimeZone())
                || !"DEMO001".equals(entity.getHospitalId())
                || !"内科".equals(entity.getDepartment())) {

            throw new IllegalStateException(
                    "历史预约字段异常，请核对：" + entity.getAppointmentId()
            );
        }

        boolean active =
                "DEMO_CREATED".equals(entity.getStatus())
                        && entity.getCancelledAt() == null;

        boolean cancelled =
                "DEMO_CANCELLED".equals(entity.getStatus())
                        && entity.getCancelledAt() != null;

        if (!active && !cancelled) {
            throw new IllegalStateException(
                    "历史预约状态或取消时间异常"
            );
        }

        Instant.parse(entity.getCreatedAt());

        if (entity.getAcceptedAt() != null) {
            Instant.parse(entity.getAcceptedAt());
        }

        if (entity.getCancelledAt() != null) {
            Instant.parse(entity.getCancelledAt());
        }

        // 历史预约允许过去日期，不改变原日期或原状态。
        return entity;
    }

    private String readRequestId(
            Document document,
            String appointmentId) {

        // 已有 requestId 的记录，继续严格检查幂等键与预约编号。
        if (document.containsKey("requestId")) {
            String requestId = required(document, "requestId");

            String expectedId = "DEMO-" + UUID.nameUUIDFromBytes(
                    ("demo-appointment:" + requestId)
                            .getBytes(StandardCharsets.UTF_8)
            );

            if (requestId.length() > 128
                    || !requestId.equals(requestId.strip())
                    || !expectedId.equals(appointmentId)) {

                throw new IllegalStateException(
                        "历史预约幂等键与编号不一致：" + appointmentId
                );
            }

            return requestId;
        }

        /*
         * 兼容这次检查发现的旧格式：
         * 没有 requestId，编号为 DEMO- 加 UUID v4。
         *
         * 缺字段的 UUID v3 记录不能直接放行，
         * 避免丢失当前版本的幂等关系。
         */
        if (!appointmentId.startsWith("DEMO-")) {
            throw new IllegalStateException(
                    "缺少 requestId 且编号格式异常：" + appointmentId
            );
        }

        String uuidText = appointmentId.substring(5);
        UUID uuid = UUID.fromString(uuidText);

        if (uuid.version() != 4
                || uuid.variant() != 2
                || !uuid.toString().equals(uuidText)) {

            throw new IllegalStateException(
                    "缺少 requestId，且不是旧版 UUID v4 编号："
                            + appointmentId
            );
        }

        if (document.containsKey("idempotencyKey")
                || document.containsKey("draftId")) {

            throw new IllegalStateException(
                    "发现其他历史请求关联字段，请先核对：" + appointmentId
            );
        }

        log.info(
                "LEGACY_APPOINTMENT_NO_REQUEST_ID id={}，保留原编号，request_id 存为 NULL",
                appointmentId
        );

        return null;
    }

    private String required(Document document, String key) {
        String value = document.getString(key);

        if (value == null || value.isBlank()) {
            throw new IllegalStateException(
                    "历史预约缺少字段：" + key
            );
        }

        return value;
    }
}