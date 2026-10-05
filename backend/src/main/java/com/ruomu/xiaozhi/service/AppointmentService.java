package com.ruomu.xiaozhi.service;

import com.mongodb.ErrorCategory;
import com.mongodb.MongoWriteException;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
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
import static com.mongodb.client.model.Filters.and;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

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

        return createAt(request, idempotencyKey, Instant.now());
    }

    /*
     * 仅供同包的草稿业务调用。
     * 时间必须来自数据库中的首次确认记录。
     */
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

    /*
     * 兼容旧版本：
     * 预约已经写入，但草稿状态尚未更新的情况。
     */
    AppointmentResponse findExistingForDraft(
            CreateAppointmentRequest request,
            String draftId) {

        String requestId = requireRequestId("draft:" + draftId);

        Document existing = collection.find(
                eq("_id", appointmentId(requestId))
        ).first();

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
        LocalDate visitDate = request.visitDate();

        if (visitDate == null
                || !visitDate.isAfter(today)
                || visitDate.isAfter(today.plusDays(3))) {

            throw badRequest("日期必须为上海时区的明天至未来第3天");
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

        Document existing = collection.find(
                eq("_id", id)
        ).first();

        // 已存在的记录优先返回，跨天重试也不会生成新预约。
        if (existing != null) {
            return reuseExisting(existing, requestId, request);
        }

        validateForConfirmation(request, acceptedAt);

        Document document = new Document("_id", id)
                .append("requestId", requestId)
                .append("status", "DEMO_CREATED")
                .append("hospitalId", normalize(request.hospitalId()))
                .append("department", normalize(request.department()))
                .append("visitDate", request.visitDate().toString())
                .append("timeZone", ZONE.getId())
                .append("acceptedAt", acceptedAt.toString())
                .append("createdAt", Instant.now().toString());

        try {
            collection.insertOne(document);
        } catch (MongoWriteException exception) {

            if (exception.getError().getCategory()
                    != ErrorCategory.DUPLICATE_KEY) {
                throw exception;
            }

            Document saved = collection.find(
                    eq("_id", id)
            ).first();

            if (saved == null) {
                throw exception;
            }

            return reuseExisting(saved, requestId, request);
        }

        return toResponse(document);
    }

    public AppointmentResponse findById(String appointmentId) {

        if (appointmentId == null || appointmentId.isBlank()) {
            throw badRequest("预约编号不能为空");
        }

        Document document = collection.find(
                eq("_id", appointmentId.strip())
        ).first();

        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到该演示预约记录"
            );
        }

        return toResponse(document);
    }

    /*
     * 仅由用户在页面明确确认后调用，不注册为大模型工具。
     * 只改变预约记录，不删除记录，也不改变草稿的确认历史。
     */
    public AppointmentResponse cancel(String appointmentId, boolean confirmed) {
        if (!confirmed) {
            throw badRequest("请明确确认取消当前演示预约");
        }
        if (appointmentId == null || appointmentId.isBlank()) {
            throw badRequest("预约编号不能为空");
        }
        String id = appointmentId.strip();

        // 单文档条件更新：只有第一次从有效状态转为取消时才记录时间。
        Document cancelled = collection.findOneAndUpdate(
                and(eq("_id", id), eq("status", "DEMO_CREATED")),
                combine(set("status", "DEMO_CANCELLED"),
                        set("cancelledAt", Instant.now().toString())),
                new FindOneAndUpdateOptions().returnDocument(ReturnDocument.AFTER)
        );
        if (cancelled != null) {
            return toResponse(cancelled);
        }

        Document existing = collection.find(eq("_id", id)).first();
        if (existing == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "未找到该演示预约记录");
        }
        if ("DEMO_CANCELLED".equals(existing.getString("status"))) {
            // 幂等重试：返回原记录，不改写首次取消时间。
            return toResponse(existing);
        }
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "当前预约状态不允许取消，请重新查询");
    }

    private AppointmentResponse reuseExisting(
            Document document,
            String requestId,
            CreateAppointmentRequest request) {

        String dateText = request.visitDate() == null
                ? null
                : request.visitDate().toString();

        boolean sameRequest =
                Objects.equals(
                        document.getString("requestId"),
                        requestId
                )
                        && Objects.equals(
                        document.getString("hospitalId"),
                        normalize(request.hospitalId())
                )
                        && Objects.equals(
                        document.getString("department"),
                        normalize(request.department())
                )
                        && Objects.equals(
                        document.getString("visitDate"),
                        dateText
                );

        if (!sameRequest) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "同一个 Idempotency-Key 不能用于不同的预约内容"
            );
        }

        return toResponse(document);
    }

    private String requireRequestId(String value) {

        if (value == null || value.isBlank()) {
            throw badRequest("Idempotency-Key 不能为空");
        }

        String requestId = value.strip();

        if (requestId.length() > 128) {
            throw badRequest("Idempotency-Key 不能超过128个字符");
        }

        return requestId;
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

    private AppointmentResponse toResponse(Document document) {

        return new AppointmentResponse(
                document.getString("_id"),
                document.getString("status"),
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                "DEMO_CANCELLED".equals(document.getString("status"))
                        ? "本地演示预约已取消，记录保留；不涉及真实医院退号或退款。"
                        : DEMO_NOTICE,
                document.getString("cancelledAt")
        );
    }
}
