package com.ruomu.xiaozhi.service;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;

@Service
public class AppointmentService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private static final String DEMO_NOTICE =
            "仅创建本地演示预约记录，不代表真实医院挂号成功。";

    private final MongoCollection<Document> collection;

    public AppointmentService(MongoTemplate mongoTemplate) {
        this.collection = mongoTemplate.getCollection("demo_appointments");
    }

    public AppointmentResponse create(
            CreateAppointmentRequest request,
            String idempotencyKey) {

        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "请求内容不能为空"
            );
        }

        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key 不能为空"
            );
        }

        String requestId = idempotencyKey.strip();

        if (requestId.length() > 128) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Idempotency-Key 不能超过128个字符"
            );
        }

        String hospitalId = request.hospitalId() == null
                ? ""
                : request.hospitalId().strip();

        String department = request.department() == null
                ? ""
                : request.department().strip();

        LocalDate visitDate = request.visitDate();

        // 同一个请求标识，始终生成相同的演示预约编号。
        String appointmentId = "DEMO-" + UUID.nameUUIDFromBytes(
                ("demo-appointment:" + requestId)
                        .getBytes(StandardCharsets.UTF_8)
        );

        Document existing = collection
                .find(eq("_id", appointmentId))
                .first();

        // 优先检查旧记录，确保跨天重试也能返回原结果。
        if (existing != null) {
            return reuseExisting(
                    existing,
                    requestId,
                    hospitalId,
                    department,
                    visitDate
            );
        }

        if (!"DEMO001".equals(hospitalId)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前仅支持演示医院 DEMO001"
            );
        }

        if (!"内科".equals(department)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "当前演示仅支持内科"
            );
        }

        LocalDate today = LocalDate.now(ZONE);

        if (visitDate == null
                || !visitDate.isAfter(today)
                || visitDate.isAfter(today.plusDays(3))) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "日期必须为上海时区的明天至未来第3天"
            );
        }

        Document document = new Document("_id", appointmentId)
                .append("requestId", requestId)
                .append("status", "DEMO_CREATED")
                .append("hospitalId", hospitalId)
                .append("department", department)
                .append("visitDate", visitDate.toString())
                .append("timeZone", ZONE.getId())
                .append("createdAt", Instant.now().toString());

        try {
            collection.insertOne(document);
        } catch (MongoWriteException exception) {

            if (exception.getError().getCategory()
                    != ErrorCategory.DUPLICATE_KEY) {
                throw exception;
            }

            // 两个相同请求同时到达时，只允许一个请求插入成功。
            Document saved = collection
                    .find(eq("_id", appointmentId))
                    .first();

            if (saved == null) {
                throw exception;
            }

            return reuseExisting(
                    saved,
                    requestId,
                    hospitalId,
                    department,
                    visitDate
            );
        }

        return toResponse(document);
    }

    public AppointmentResponse findById(String appointmentId) {

        Document document = collection
                .find(eq("_id", appointmentId))
                .first();

        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到该演示预约记录"
            );
        }

        return toResponse(document);
    }

    private AppointmentResponse reuseExisting(
            Document document,
            String requestId,
            String hospitalId,
            String department,
            LocalDate visitDate) {

        String dateText = visitDate == null
                ? null
                : visitDate.toString();

        boolean sameRequest =
                Objects.equals(document.getString("requestId"), requestId)
                        && Objects.equals(
                        document.getString("hospitalId"), hospitalId)
                        && Objects.equals(
                        document.getString("department"), department)
                        && Objects.equals(
                        document.getString("visitDate"), dateText);

        if (!sameRequest) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "同一个 Idempotency-Key 不能用于不同的预约内容"
            );
        }

        return toResponse(document);
    }

    private AppointmentResponse toResponse(Document document) {

        return new AppointmentResponse(
                document.getString("_id"),
                document.getString("status"),
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                DEMO_NOTICE
        );
    }
}