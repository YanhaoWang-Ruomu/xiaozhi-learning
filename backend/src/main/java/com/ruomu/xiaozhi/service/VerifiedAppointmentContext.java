package com.ruomu.xiaozhi.service;
import org.springframework.stereotype.Service;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
/** Structured facts come only from persisted owned drafts, never from model-generated summaries. */
@Service
public class VerifiedAppointmentContext {
    private final OwnedAppointmentService owned;
    private final ConversationHistoryService history;
    public VerifiedAppointmentContext(OwnedAppointmentService owned,ConversationHistoryService history){this.owned=owned;this.history=history;}
    public String get(String conversationId){
        if(conversationId==null)return "";
        var drafts=owned.list(conversationId,history.ownerAccount(conversationId));
        if(drafts.isEmpty())return "";
        var d=drafts.get(0);
        try{return new ObjectMapper().writeValueAsString(Map.of("source","OWNED_DATABASE_RECORD","status",d.status(),
            "hospitalId",d.hospitalId(),"department",d.department(),"visitDate",d.visitDate().toString(),
            "draftId",d.draftId(),"sessionId",d.session()==null?"":d.session().sessionId()));}
        catch(Exception e){throw new IllegalStateException("Structured context unavailable");}
    }
}
