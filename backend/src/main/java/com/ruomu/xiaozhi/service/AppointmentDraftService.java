package com.ruomu.xiaozhi.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.FindOneAndUpdateOptions;
import com.mongodb.client.model.ReturnDocument;
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

import static com.mongodb.client.model.Filters.and;
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

        Document draft = recoverLegacyAppointment(
                requireDraft(draftId)
        );

        String id = draft.getString("_id");

        if ("PENDING_CONFIRMATION".equals(draft.getString("status"))) {

            /*
             * 校验失败时不改变草稿。
             * 首次确认时间由服务器决定，并保存到数据库。
             */
            Instant acceptedAt = Instant.now();

            appointmentService.validateForConfirmation(
                    toRequest(draft),
                    acceptedAt
            );

            Document claimed = collection.findOneAndUpdate(
                    and(
                            eq("_id", id),
                            eq("status", "PENDING_CONFIRMATION"),
                            eq("appointmentId", null)
                    ),
                    combine(
                            set("status", "CONFIRMING"),
                            set(
                                    "confirmationStartedAt",
                                    acceptedAt.toString()
                            )
                    ),
                    new FindOneAndUpdateOptions()
                            .returnDocument(ReturnDocument.AFTER)
            );

            /*
             * 如果取消或另一个确认先完成了状态更新，
             * 必须重新读取数据库。
             */
            draft = claimed == null
                    ? requireDraft(id)
                    : claimed;
        }

        String status = draft.getString("status");

        if ("CANCELLED".equals(status)) {
            throw conflict(
                    "草稿已取消，不能再确认，请重新创建草稿"
            );
        }

        if ("CONFIRMED".equals(status)) {
            return requireActiveAppointment(draft.getString("appointmentId"));
        }

        if (!"CONFIRMING".equals(status)) {
            throw conflict(
                    "当前草稿状态不允许确认，请重新查询"
            );
        }

        String acceptedAtText = draft.getString(
                "confirmationStartedAt"
        );

        if (acceptedAtText == null) {
            throw conflict(
                    "草稿缺少首次确认时间，请检查后台记录"
            );
        }

        /*
         * CONFIRMING 是持久化的确认决定。
         *
         * 异常时不退回待确认状态，因为预约可能已经写入，
         * 只是客户端未收到成功响应。
         *
         * 重试同一草稿时，使用原幂等键和首次确认时间，
         * 继续完成写入。
         */
        AppointmentResponse appointment =
                appointmentService.createForConfirmedDraft(
                        toRequest(draft),
                        id,
                        Instant.parse(acceptedAtText)
                );

        var result = collection.updateOne(
                and(
                        eq("_id", id),
                        eq("status", "CONFIRMING"),
                        eq("appointmentId", null)
                ),
                combine(
                        set("status", "CONFIRMED"),
                        set(
                                "appointmentId",
                                appointment.appointmentId()
                        ),
                        set(
                                "confirmedAt",
                                Instant.now().toString()
                        )
                )
        );

        if (result.getMatchedCount() == 0) {

            Document latest = requireDraft(id);

            if (!"CONFIRMED".equals(latest.getString("status"))
                    || !appointment.appointmentId().equals(
                    latest.getString("appointmentId")
            )) {

                throw conflict(
                        "预约结果与草稿状态不一致，请检查后台记录"
                );
            }
        }

        // 返回前重新查询，不能将已经取消的预约当作新创建成功。
        return requireActiveAppointment(appointment.appointmentId());
    }

    private AppointmentResponse requireActiveAppointment(String appointmentId) {
        AppointmentResponse appointment = appointmentService.findById(appointmentId);
        if ("DEMO_CANCELLED".equals(appointment.status())) {
            throw conflict("该演示预约已取消，不能通过再次确认原草稿恢复；如需预约请新建草稿");
        }
        if (!"DEMO_CREATED".equals(appointment.status())) {
            throw conflict("预约状态异常，请重新查询");
        }
        return appointment;
    }

    public AppointmentDraftResponse cancel(String draftId) {

        Document draft = recoverLegacyAppointment(
                requireDraft(draftId)
        );

        String id = draft.getString("_id");

        if ("PENDING_CONFIRMATION".equals(draft.getString("status"))) {

            Document cancelled = collection.findOneAndUpdate(
                    and(
                            eq("_id", id),
                            eq("status", "PENDING_CONFIRMATION"),
                            eq("appointmentId", null)
                    ),
                    combine(
                            set("status", "CANCELLED"),
                            set(
                                    "cancelledAt",
                                    Instant.now().toString()
                            )
                    ),
                    new FindOneAndUpdateOptions()
                            .returnDocument(ReturnDocument.AFTER)
            );

            draft = cancelled == null
                    ? requireDraft(id)
                    : cancelled;
        }

        return switch (draft.getString("status")) {

            // 重复取消返回相同业务结果，不删除记录。
            case "CANCELLED" -> toResponse(draft);

            case "CONFIRMED" -> throw conflict(
                    "草稿已确认，不能通过取消草稿来取消预约"
            );

            case "CONFIRMING" -> throw conflict(
                    "草稿已进入确认处理，不能取消；请重试原确认请求以完成处理"
            );

            default -> throw conflict(
                    "当前草稿状态不允许取消，请重新查询"
            );
        };
    }

    private CreateAppointmentRequest toRequest(Document draft) {

        return new CreateAppointmentRequest(
                draft.getString("hospitalId"),
                draft.getString("department"),
                LocalDate.parse(draft.getString("visitDate"))
        );
    }

    /*
     * 旧代码可能在写入预约后中断，留下仍为待确认的草稿。
     *
     * 取消前先检查原幂等键对应的预约，
     * 避免错误地取消这种草稿。
     */
    private Document recoverLegacyAppointment(Document draft) {

        if (!"PENDING_CONFIRMATION".equals(draft.getString("status"))) {
            return draft;
        }

        String id = draft.getString("_id");

        AppointmentResponse existing =
                appointmentService.findExistingForDraft(
                        toRequest(draft),
                        id
                );

        if (existing == null) {
            return draft;
        }

        Document repaired = collection.findOneAndUpdate(
                and(
                        eq("_id", id),
                        eq("status", "PENDING_CONFIRMATION"),
                        eq("appointmentId", null)
                ),
                combine(
                        set("status", "CONFIRMED"),
                        set(
                                "appointmentId",
                                existing.appointmentId()
                        ),
                        set(
                                "reconciledAt",
                                Instant.now().toString()
                        )
                ),
                new FindOneAndUpdateOptions()
                        .returnDocument(ReturnDocument.AFTER)
        );

        return repaired == null
                ? requireDraft(id)
                : repaired;
    }

    private ResponseStatusException conflict(String message) {

        return new ResponseStatusException(
                HttpStatus.CONFLICT,
                message
        );
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

        // 数据库草稿仍为 CONFIRMED，代表历史上已确认。
        // 页面状态从关联预约实时推导，取消状态只在预约集合保存一份。
        if ("CONFIRMED".equals(status)) {
            AppointmentResponse appointment = appointmentService.findById(
                    document.getString("appointmentId"));
            if ("DEMO_CANCELLED".equals(appointment.status())) {
                status = "APPOINTMENT_CANCELLED";
            } else if (!"DEMO_CREATED".equals(appointment.status())) {
                throw conflict("关联预约状态异常，请检查后台记录");
            }
        }

        String message = switch (status) {

            case "PENDING_CONFIRMATION" ->
                    "仅生成待确认草稿，尚未创建预约，请核对内容后再确认。";

            case "CONFIRMING" ->
                    "确认处理尚未完成，不能取消；可重试同一草稿的确认请求以继续处理。";

            case "CONFIRMED" ->
                    "已确认并创建本地演示预约，不代表真实医院挂号成功。";

            case "APPOINTMENT_CANCELLED" ->
                    "关联的演示预约已取消，草稿与预约记录均保留；不能再次确认恢复，请按需新建草稿。";

            case "CANCELLED" ->
                    "草稿已取消，不能再确认；如仍需预约，请重新创建草稿。";

            default ->
                    "草稿状态异常，请检查后台记录。";
        };

        return new AppointmentDraftResponse(
                document.getString("_id"),
                status,
                document.getString("hospitalId"),
                document.getString("department"),
                LocalDate.parse(document.getString("visitDate")),
                document.getString("timeZone"),
                document.getString("appointmentId"),
                message
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
