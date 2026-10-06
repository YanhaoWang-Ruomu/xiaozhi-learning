package com.ruomu.xiaozhi.service;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Indexes;
import com.ruomu.xiaozhi.dto.*;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
import static com.mongodb.client.model.Filters.eq;

/** 归属沿草稿 -> 会话 -> 账号检查。无归属凭据的旧记录默认拒绝，不自动分配。 */
@Service
public class OwnedAppointmentService {
    private final ConversationHistoryService history;
    private final AppointmentDraftService drafts;
    private final AppointmentService appointments;
    private final MongoCollection<Document> collection;
    public OwnedAppointmentService(ConversationHistoryService history, AppointmentDraftService drafts,
                                   AppointmentService appointments, MongoTemplate mongo) {
        this.history = history; this.drafts = drafts; this.appointments = appointments;
        collection = mongo.getCollection("demo_appointment_drafts");
        collection.createIndex(Indexes.ascending("appointmentId"));
        collection.createIndex(Indexes.ascending("conversationId"));
    }
    public AppointmentDraftResponse create(CreateAppointmentRequest request, String conversationId, String userId) {
        history.requireOwned(conversationId, userId);
        return drafts.createDraft(request, conversationId);
    }
    // 只供已验证会话的聊天工具调用。ToolMemoryId 由AI Services注入，不是模型参数或ThreadLocal。
    public AppointmentDraftResponse createFromChat(CreateAppointmentRequest request, String trustedConversationId) {
        return create(request, trustedConversationId, history.ownerAccount(trustedConversationId));
    }
    public List<AppointmentDraftResponse> list(String conversationId, String userId) {
        history.requireOwned(conversationId, userId);
        return drafts.findByConversationId(conversationId);
    }
    public AppointmentDraftResponse findDraft(String id, String userId) {
        requireDraft(id, userId);
        return drafts.findById(id);
    }
    public AppointmentResponse confirm(String id, String userId) {
        requireDraft(id, userId);
        return drafts.confirm(id);
    }
    public AppointmentDraftResponse cancelDraft(String id, String userId) {
        requireDraft(id, userId);
        return drafts.cancel(id);
    }
    public AppointmentResponse findAppointment(String id, String userId) {
        requireAppointment(id, userId);
        return appointments.findById(id);
    }
    public AppointmentResponse cancelAppointment(String id, boolean confirmed, String userId) {
        requireAppointment(id, userId);
        return appointments.cancel(id, confirmed);
    }
    private void requireDraft(String id, String userId) {
        Document draft = id == null ? null : collection.find(eq("_id", id)).first();
        requireOwner(draft, userId);
    }
    private void requireAppointment(String id, String userId) {
        Document draft = id == null ? null : collection.find(eq("appointmentId", id)).first();
        requireOwner(draft, userId);
    }
    private void requireOwner(Document draft, String userId) {
        if (draft == null || draft.getString("conversationId") == null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "记录不存在或不属于当前账号");
        history.requireOwned(draft.getString("conversationId"), userId);
    }
}
