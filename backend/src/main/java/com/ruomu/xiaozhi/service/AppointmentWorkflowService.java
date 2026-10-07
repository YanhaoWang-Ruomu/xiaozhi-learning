package com.ruomu.xiaozhi.service;
import com.mongodb.client.MongoCollection;
import com.ruomu.xiaozhi.dto.*;
import org.bson.Document;
import org.springframework.stereotype.Service;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.util.*;
import static com.mongodb.client.model.Filters.*;
import static com.mongodb.client.model.Updates.*;

/** Explicit, durable single-instance workflow experiment. No autonomous confirmation. */
@Service
public class AppointmentWorkflowService {
    public record Requirements(long version,String hospitalId,String department,LocalDate visitDate,String sessionId) {}
    public record Checkpoint(String id,String conversationId,String phase,long version,Map<String,Object> requirements,
                             String draftId,AppointmentDraftResponse draft) {}
    private final MongoCollection<Document> rows;
    private final ConversationHistoryService history;
    private final AppointmentDraftService drafts;
    private final OwnedAppointmentService owned;
    private final AppointmentSessionService sessions;
    public AppointmentWorkflowService(MongoTemplate mongo,ConversationHistoryService history,AppointmentDraftService drafts,
                                      OwnedAppointmentService owned,AppointmentSessionService sessions){
        this.rows=mongo.getCollection("appointment_workflows");this.history=history;this.drafts=drafts;this.owned=owned;this.sessions=sessions;
        rows.createIndex(com.mongodb.client.model.Indexes.compoundIndex(com.mongodb.client.model.Indexes.ascending("userId"),com.mongodb.client.model.Indexes.ascending("conversationId")));
    }
    public Checkpoint create(String conversationId,String user){
        history.requireOwned(conversationId,user);
        String id=UUID.randomUUID().toString();
        rows.insertOne(new Document("_id",id).append("conversationId",conversationId).append("userId",user)
            .append("phase","UNDERSTANDING").append("version",0L).append("requirements",new Document()).append("createdAt",Instant.now().toString()));
        return get(id,user);
    }
    private Document row(String id,String user){
        Document r=rows.find(and(eq("_id",id),eq("userId",user))).first();
        if(r==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"工作流不存在");
        history.requireOwned(r.getString("conversationId"),user);return r;
    }
    public Checkpoint get(String id,String user){
        var r=row(id,user);AppointmentDraftResponse draft=null;String phase=r.getString("phase");
        if(r.getString("draftId")!=null){
            try{draft=owned.findDraft(r.getString("draftId"),user);phase=draft.status();}
            catch(ResponseStatusException e){if(e.getStatusCode().value()!=404)throw e;phase="RECOVERY_REQUIRED";}
        }
        return new Checkpoint(id,r.getString("conversationId"),phase,r.getLong("version"),
            Collections.unmodifiableMap(new LinkedHashMap<>(r.get("requirements",Document.class))),r.getString("draftId"),draft);
    }
    public Checkpoint requirements(String id,String user,Requirements req){
        var r=row(id,user);
        if(r.getString("draftId")!=null)throw new ResponseStatusException(HttpStatus.CONFLICT,"草稿创建后不能更改原需求，请新建工作流");
        if(req==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"需求不能为空");
        for(String s:new String[]{req.hospitalId(),req.department(),req.sessionId()})if(s!=null&&s.length()>80)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"字段过长");
        Document facts=new Document();
        if(req.hospitalId()!=null&&!req.hospitalId().isBlank())facts.append("hospitalId",req.hospitalId().strip());
        if(req.department()!=null&&!req.department().isBlank())facts.append("department",req.department().strip());
        if(req.visitDate()!=null)facts.append("visitDate",req.visitDate().toString());
        if(req.sessionId()!=null&&!req.sessionId().isBlank())facts.append("sessionId",req.sessionId().strip());
        update(id,user,req.version(),combine(set("requirements",facts),set("phase",facts.size()==4?"READY_FOR_DRAFT":"CLARIFYING")));
        return get(id,user);
    }
    public AppointmentSessionResponse query(String id,String user){
        var r=row(id,user);var facts=r.get("requirements",Document.class);
        if(!facts.containsKey("hospitalId")||!facts.containsKey("department"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"先明确医院和科室");
        return com.ruomu.xiaozhi.observability.AiTelemetry.call("workflow.query",()->sessions.findSessions(facts.getString("hospitalId"),facts.getString("department")));
    }
    public Checkpoint draft(String id,String user,long version,boolean reviewed){
        if(!reviewed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请先核对需求");
        var r=row(id,user);var f=r.get("requirements",Document.class);
        if(f.size()!=4)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请补充完整医院、科室、日期和场次");
        String stable="DRAFT-FLOW-"+id;
        if(r.getString("draftId")==null){update(id,user,version,combine(set("phase","DRAFT_CREATING"),set("draftId",stable)));}
        else if(!stable.equals(r.getString("draftId")))throw new ResponseStatusException(HttpStatus.CONFLICT,"草稿标识异常");
        // Repeating an interrupted request reuses one stable ID, never silently creates a second draft.
        var request=new CreateAppointmentRequest(f.getString("hospitalId"),f.getString("department"),LocalDate.parse(f.getString("visitDate")),f.getString("sessionId"));
        com.ruomu.xiaozhi.observability.AiTelemetry.call("workflow.draft",()->drafts.createWorkflowDraft(request,r.getString("conversationId"),stable));
        return get(id,user);
    }
    public Checkpoint confirm(String id,String user,boolean confirmed){
        if(!confirmed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"必须明确确认");
        var r=row(id,user);if(r.getString("draftId")==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"尚未生成草稿");
        com.ruomu.xiaozhi.observability.AiTelemetry.call("workflow.confirm",()->owned.confirm(r.getString("draftId"),user));
        return get(id,user);
    }
    public Checkpoint cancel(String id,String user,boolean confirmed){
        if(!confirmed)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"必须明确确认取消");
        var cp=get(id,user);if(cp.draft()==null)throw new ResponseStatusException(HttpStatus.CONFLICT,"尚未生成草稿");
        if(cp.draft().appointmentId()!=null)owned.cancelAppointment(cp.draft().appointmentId(),true,user);
        else owned.cancelDraft(cp.draftId(),user);
        return get(id,user);
    }
    private void update(String id,String user,long version,org.bson.conversions.Bson mutation){
        var result=rows.updateOne(and(eq("_id",id),eq("userId",user),eq("version",version)),
            combine(mutation,inc("version",1L),set("updatedAt",Instant.now().toString())));
        if(result.getMatchedCount()!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"工作流已变更，请刷新后重试");
    }
}
