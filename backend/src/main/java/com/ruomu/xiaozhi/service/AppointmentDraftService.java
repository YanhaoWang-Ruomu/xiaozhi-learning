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
import java.util.UUID;

import static com.mongodb.client.model.Filters.eq;
import static com.mongodb.client.model.Updates.combine;
import static com.mongodb.client.model.Updates.set;

@Service
public class AppointmentDraftService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    private final MongoCollection<Document> collection;
    private final AppointmentService appointmentService;

    public AppointmentDraftService(
            MongoTemplate mongoTemplate,
            AppointmentService appointmentService) {

        this.collection =
                mongoTemplate.getCollection("demo_appointment_drafts");

        this.appointmentService = appointmentService;
    }

    public AppointmentDraftResponse createDraft(
            CreateAppointmentRequest request) {

        if (request == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "请求内容不能为空"
            );
        }

        String hospitalId = request.hospitalId() == null
                ? ""
                : request.hospitalId().strip();

        String department = request.department() == null
                ? ""
                : request.department().strip();

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

        LocalDate visitDate = request.visitDate();
        LocalDate today = LocalDate.now(ZONE);

        if (visitDate == null
                || !visitDate.isAfter(today)
                || visitDate.isAfter(today.plusDays(3))) {

            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "日期必须为上海时区的明天至未来第3天"
            );
        }

        String draftId = "DRAFT-" + UUID.randomUUID();

        Document document = new Document("_id", draftId)
                .append("status", "PENDING_CONFIRMATION")
                .append("hospitalId", hospitalId)
                .append("department", department)
                .append("visitDate", visitDate.toString())
                .append("timeZone", ZONE.getId())
                .append("createdAt", Instant.now().toString());

        // 此处只保存草稿，不调用创建预约的方法。
        collection.insertOne(document);

        return toResponse(document);
    }

    public AppointmentDraftResponse findById(String draftId) {
        return toResponse(requireDraft(draftId));
    }

    public AppointmentResponse confirm(String draftId) {

        Document draft = requireDraft(draftId);

        // 已经确认过的草稿，直接返回原预约。
        String appointmentId = draft.getString("appointmentId");

        if (appointmentId != null) {
            return appointmentService.findById(appointmentId);
        }

        // 确认时只使用数据库中的草稿内容。
        CreateAppointmentRequest request =
                new CreateAppointmentRequest(
                        draft.getString("hospitalId"),
                        draft.getString("department"),
                        LocalDate.parse(draft.getString("visitDate"))
                );

        // 同一草稿使用固定的请求标识，复用已有的重复提交保护。
        AppointmentResponse appointment = appointmentService.create(
                request,
                "draft:" + draftId
        );

        // 创建预约成功后，再把草稿标记为已确认。
        collection.updateOne(
                eq("_id", draftId),
                combine(
                        set("status", "CONFIRMED"),
                        set("appointmentId", appointment.appointmentId())
                )
        );

        return appointment;
    }

    private Document requireDraft(String draftId) {

        Document document = collection
                .find(eq("_id", draftId))
                .first();

        if (document == null) {
            throw new ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "未找到该预约草稿"
            );
        }

        return document;
    }

    private AppointmentDraftResponse toResponse(Document document) {

        boolean confirmed =
                "CONFIRMED".equals(document.getString("status"));

        String message = confirmed
                ? "已确认并创建本地演示预约，不代表真实医院挂号成功。"
                : "仅生成待确认草稿，尚未创建预约，请核对内容后再确认。";

        return new AppointmentDraftResponse(
                document.getString("_id"),
                document.getString("status"),
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                document.getString("appointmentId"),
                message
        );
    }
}