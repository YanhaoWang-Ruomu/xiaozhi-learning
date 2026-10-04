package com.ruomu.xiaozhi.service;

import com.mongodb.client.MongoCollection;
import com.ruomu.xiaozhi.dto.AppointmentDraftResponse;
import com.ruomu.xiaozhi.dto.AppointmentResponse;
import com.ruomu.xiaozhi.dto.CreateAppointmentRequest;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Sorts.descending;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

@Service
public class AppointmentDraftService {

    private static final String TIME_ZONE = "Asia/Shanghai";

    private final MongoCollection<Document> collection;
    private final AppointmentService appointmentService;

    public AppointmentDraftService(
            MongoTemplate mongoTemplate,
            AppointmentService appointmentService) {

        this.collection = mongoTemplate.getCollection(
                "demo_appointment_drafts"
        );
        this.appointmentService = appointmentService;
    }

    /*
     * 保留原来的调用方式，兼容不携带会话编号的手动接口。
     * 这种草稿只能按草稿编号查询。
     */
    public AppointmentDraftResponse createDraft(
            CreateAppointmentRequest request) {

        return insertDraft(request, null);
    }

    /*
     * 聊天工具使用这个方法。
     * 会话编号和草稿在同一次插入中保存。
     */
    public AppointmentDraftResponse createDraft(
            CreateAppointmentRequest request,
            String conversationId) {

        String normalizedId = requireConversationId(conversationId);
        return insertDraft(request, normalizedId);
    }

    private AppointmentDraftResponse insertDraft(
            CreateAppointmentRequest request,
            String conversationId) {

        if (request == null) {
            throw badRequest("预约请求不能为空");
        }

        String hospitalId = normalize(request.hospitalId());
        String department = normalize(request.department());
        LocalDate visitDate = request.visitDate();

        if (!"DEMO001".equals(hospitalId)) {
            throw badRequest("当前仅支持演示医院 DEMO001");
        }

        if (!"内科".equals(department)) {
            throw badRequest("当前仅支持演示科室：内科");
        }

        if (visitDate == null) {
            throw badRequest("请提供预约日期");
        }

        LocalDate today = LocalDate.now(ZoneId.of(TIME_ZONE));

        if (!visitDate.isAfter(today)
                || visitDate.isAfter(today.plusDays(3))) {
            throw badRequest(
                    "预约日期必须是上海时区明天起的未来 3 天内"
            );
        }

        String draftId = "DRAFT-" + UUID.randomUUID();

        Document document = new Document("_id", draftId)
                .append("status", "PENDING_CONFIRMATION")
                .append("hospitalId", hospitalId)
                .append("department", department)
                .append("visitDate", visitDate.toString())
                .append("timeZone", TIME_ZONE)
                .append("createdAt", Instant.now().toString());

        if (conversationId != null) {
            document.append("conversationId", conversationId);
        }

        collection.insertOne(document);

        return toResponse(document);
    }

    public AppointmentDraftResponse findById(String draftId) {
        return toResponse(requireDraft(draftId));
    }

    /*
     * 查询指定会话最近的 100 份草稿。
     * 旧的、没有 conversationId 字段的草稿不会出现在这里。
     */
    public List<AppointmentDraftResponse> findByConversationId(
            String conversationId) {

        String normalizedId = requireConversationId(conversationId);

        List<AppointmentDraftResponse> responses = new ArrayList<>();

        collection.find(eq("conversationId", normalizedId))
                .sort(descending("createdAt", "_id"))
                .limit(100)
                .forEach(document -> responses.add(toResponse(document)));

        return responses;
    }

    public AppointmentResponse confirm(String draftId) {
        Document draft = requireDraft(draftId);

        String appointmentId = draft.getString("appointmentId");

        if (appointmentId != null) {
            return appointmentService.findById(appointmentId);
        }

        CreateAppointmentRequest request = new CreateAppointmentRequest(
                draft.getString("hospitalId"),
                draft.getString("department"),
                LocalDate.parse(draft.getString("visitDate"))
        );

        /*
         * 同一份草稿始终使用同一个幂等键。
         * 重复确认不会为这份草稿重复创建预约。
         */
        AppointmentResponse appointment = appointmentService.create(
                request,
                "draft:" + draft.getString("_id")
        );

        collection.updateOne(
                eq("_id", draft.getString("_id")),
                combine(
                        set("status", "CONFIRMED"),
                        set("appointmentId", appointment.appointmentId())
                )
        );

        return appointment;
    }

    private Document requireDraft(String draftId) {
        if (draftId == null || draftId.isBlank()) {
            throw badRequest("草稿编号不能为空");
        }

        Document document = collection.find(
                eq("_id", draftId.strip())
        ).first();

        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到对应草稿"
            );
        }

        return document;
    }

    private AppointmentDraftResponse toResponse(Document document) {
        String status = document.getString("status");
        boolean confirmed = "CONFIRMED".equals(status);

        return new AppointmentDraftResponse(
                document.getString("_id"),
                status,
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                document.getString("appointmentId"),
                confirmed
                        ? "已确认并创建本地演示预约，不代表真实医院挂号成功。"
                        : "仅生成待确认草稿，尚未创建预约，请核对内容后再确认。"
        );
    }

    private String requireConversationId(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            throw badRequest("会话编号不能为空");
        }

        String normalizedId = conversationId.strip();

        if (normalizedId.length() > 128) {
            throw badRequest("会话编号不能超过 128 个字符");
        }

        return normalizedId;
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
}