package com.ruomu.examples.agentic;

import dev.langchain4j.agentic.Agent;
import dev.langchain4j.agentic.AgenticServices;
import dev.langchain4j.agentic.UntypedAgent;
import dev.langchain4j.service.V;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Official sequence orchestration compared with the same explicit Java workflow. No writes or model keys. */
public final class BookingWorkflow {
    public record Request(String sessionId,boolean explicitSelection,boolean confirmInChat) {}
    public record Facts(String sessionId,String status,int remaining) {}
    public record Decision(String status,String sessionId,int appointmentWrites) {}
    public interface ScheduleSource { Facts lookup(String sessionId); }
    public static final class Query {
        private final ScheduleSource source;private final AtomicInteger calls=new AtomicInteger();
        public Query(ScheduleSource source){this.source=source;}
        @Agent(outputKey="facts")
        public Facts query(@V("request") Request request){
            if(request==null)throw new IllegalArgumentException("request");
            if(!request.explicitSelection()||request.sessionId()==null||request.sessionId().isBlank()||request.confirmInChat())
                return new Facts("","CLARIFY",0);
            calls.incrementAndGet();
            try{return source.lookup(request.sessionId());}
            catch(RuntimeException error){return new Facts(request.sessionId(),"QUERY_FAILED",0);}
        }
        public int calls(){return calls.get();}
    }
    public static final class Verify {
        @Agent(outputKey="decision")
        public Decision verify(@V("request") Request request,@V("facts") Facts facts){
            if(request.confirmInChat())return new Decision("HUMAN_CONFIRMATION_REQUIRED","",0);
            if(!request.explicitSelection()||facts==null||"CLARIFY".equals(facts.status()))return new Decision("CLARIFY","",0);
            if(!Objects.equals(request.sessionId(),facts.sessionId()))return new Decision("MISMATCH","",0);
            if("QUERY_FAILED".equals(facts.status()))return new Decision("QUERY_FAILED","",0);
            if(!"AVAILABLE".equals(facts.status())||facts.remaining()<=0)return new Decision("UNAVAILABLE","",0);
            return new Decision("PENDING_CONFIRMATION",facts.sessionId(),0);
        }
    }
    private final Query query;private final Verify verify=new Verify();private final UntypedAgent official;
    public BookingWorkflow(ScheduleSource source){
        query=new Query(source);
        official=AgenticServices.sequenceBuilder().subAgents(query,verify).outputKey("decision").build();
    }
    public Decision custom(Request request){return verify.verify(request,query.query(request));}
    public Decision official(Request request){return (Decision)official.invoke(Map.of("request",request));}
    public int queries(){return query.calls();}
}
