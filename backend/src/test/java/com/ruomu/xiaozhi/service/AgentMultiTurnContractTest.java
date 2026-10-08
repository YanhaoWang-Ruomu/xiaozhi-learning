package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.bson.Document;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real databases and production services. No language-model reasoning is simulated or scored. */
@EnabledIfSystemProperty(named="xiaozhi.mysql.integration", matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(60)
class AgentMultiTurnContractTest {
    static final String OWNER="11111111-1111-1111-1111-111111111111";
    private final AppointmentMySqlIntegrationTest db=new AppointmentMySqlIntegrationTest();
    private final List<Map<String,Object>> trace=new ArrayList<>();
    private boolean failed;
    @BeforeAll void open() throws Exception { db.openIsolatedDatabases(); }
    @AfterAll void close() { db.closeOnlyOurDatabases(); }
    @BeforeEach void reset() { db.resetOnlyOurFixture(); trace.clear(); failed=false; }
    @AfterEach void report(TestInfo info) throws Exception {
        Path dir=Path.of("target/agent-multiturn/contracts"); Files.createDirectories(dir);
        new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(dir.resolve(info.getTestMethod().orElseThrow().getName()+".json").toFile(),
            Map.of("mode","DETERMINISTIC_SERVICES_REAL_MYSQL_MONGO", "status",failed?"FAIL":"PASS",
                "modelReasoningEvaluated",false,"steps",trace));
    }
    private void step(String name, Runnable action) {
        var row=new LinkedHashMap<String,Object>(); row.put("step",name);
        try { action.run(); row.put("status","PASS"); }
        catch(RuntimeException|AssertionError e) { failed=true; row.put("status","FAIL"); row.put("errorType",e.getClass().getSimpleName()); throw e; }
        finally {
            row.put("activeAppointments",db.active()); row.put("retainedAppointments",db.count("demo_appointments"));
            row.put("draftCount",db.mongo.getCollection("demo_appointment_drafts").countDocuments()); trace.add(row);
        }
    }
    private OwnedAppointmentService owned() { return new OwnedAppointmentService(new ConversationHistoryService(db.mongo),db.drafts,db.service,db.mongo); }
    private void rejected(int code,Runnable action) {
        assertEquals(code,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());
    }
    private AppointmentWorkflowService.Requirements selection(AppointmentWorkflowService.Checkpoint cp,String session) {
        return new AppointmentWorkflowService.Requirements(cp.version(),"DEMO001","内科",LocalDate.parse(cp.requirements().get("visitDate").toString()),session);
    }

    @Test void changingSelectionBeforeDraftUsesLatestSlotAndRejectsStaleUpdate() {
        var cp=db.readyWorkflow(); var flow=db.workflow();
        step("change morning to afternoon",()->flow.requirements(cp.id(),OWNER,selection(cp,"2")));
        step("old tab cannot restore morning",()->rejected(409,()->flow.requirements(cp.id(),OWNER,selection(cp,"1"))));
        step("restart services and draft latest choice",()->{
            var restored=db.workflow().get(cp.id(),OWNER);
            assertEquals("2",restored.requirements().get("sessionId"));
            var draft=db.workflow().draft(cp.id(),OWNER,restored.version(),true);
            assertEquals("2",draft.draft().session().sessionId()); assertEquals(0,db.active());
        });
    }

    @Test void mismatchedDateAndSessionNeverProduceDraftOrAppointment() {
        var cp=db.readyWorkflow(); var flow=db.workflow();
        step("change date but retain old session",()->flow.requirements(cp.id(),OWNER,
            new AppointmentWorkflowService.Requirements(cp.version(),"DEMO001","内科",LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(2),"1")));
        step("reject contradictory target",()->{
            var latest=flow.get(cp.id(),OWNER);
            assertThrows(ResponseStatusException.class,()->flow.draft(cp.id(),OWNER,latest.version(),true));
            assertEquals(0,db.mongo.getCollection("demo_appointment_drafts").countDocuments()); assertEquals(0,db.active());
        });
    }

    @Test void removingChoiceRequiresClarificationAndCannotReuseOldSlot() {
        var cp=db.readyWorkflow(); var flow=db.workflow();
        step("withdraw the selected slot",()->flow.requirements(cp.id(),OWNER,selection(cp,null)));
        step("missing selection blocks draft",()->{
            var latest=flow.get(cp.id(),OWNER); assertEquals("CLARIFYING",latest.phase());
            assertFalse(latest.requirements().containsKey("sessionId"));
            rejected(400,()->flow.draft(cp.id(),OWNER,latest.version(),true)); assertEquals(0,db.active());
        });
    }

    @Test void queryFailurePreservesRequirementsAndExplicitRetryRecovers() {
        var cp=db.readyWorkflow(); var sessions=mock(AppointmentSessionService.class);
        when(sessions.findSessions("DEMO001","内科")).thenThrow(new IllegalStateException("synthetic query failure"));
        var flow=new AppointmentWorkflowService(db.mongo,new ConversationHistoryService(db.mongo),db.drafts,owned(),sessions);
        step("query fails without a write",()->{
            assertThrows(IllegalStateException.class,()->flow.query(cp.id(),OWNER));
            assertEquals(cp.requirements(),flow.get(cp.id(),OWNER).requirements());
            assertEquals(cp.version(),flow.get(cp.id(),OWNER).version()); assertEquals(0,db.active());
        });
        step("retry with recovered database service",()->{
            assertFalse(db.workflow().query(cp.id(),OWNER).sessions().isEmpty());
            assertEquals(0,db.mongo.getCollection("demo_appointment_drafts").countDocuments());
        });
    }

    @Test void capacityChangesAfterDraftCannotOversellOnConfirmation() {
        var cp=db.readyWorkflow(); var flow=db.workflow();
        step("prepare draft without reserving",()->{ flow.draft(cp.id(),OWNER,cp.version(),true); assertEquals(0,db.active()); });
        step("capacity is removed before confirmation",()->db.capacity(0,0));
        step("reject stale availability and preserve cancelled draft",()->{
            assertThrows(ResponseStatusException.class,()->flow.confirm(cp.id(),OWNER,true));
            assertEquals(0,db.active()); assertEquals(0,db.count("demo_appointments"));
            assertEquals("CANCELLED",flow.get(cp.id(),OWNER).phase());
        });
    }

    @Test void cancellationSurvivesServiceRecreationWithoutReplay() {
        var cp=db.readyWorkflow(); var flow=db.workflow();
        step("explicitly confirm",()->{flow.draft(cp.id(),OWNER,cp.version(),true);flow.confirm(cp.id(),OWNER,true);assertEquals(1,db.active());});
        step("explicitly cancel",()->{flow.cancel(cp.id(),OWNER,true);assertEquals(0,db.active());});
        step("read and retry after service recreation",()->{
            var restored=db.workflow(); assertEquals("APPOINTMENT_CANCELLED",restored.get(cp.id(),OWNER).phase());
            rejected(409,()->restored.confirm(cp.id(),OWNER,true));
            restored.cancel(cp.id(),OWNER,true); assertEquals(0,db.active()); assertEquals(1,db.count("demo_appointments"));
        });
    }

    @Test void interruptedDraftWriteRecoversOneStableDraft() {
        var cp=db.readyWorkflow(); var failing=spy(db.drafts);
        doThrow(new IllegalStateException("synthetic draft write failure")).when(failing).createWorkflowDraft(any(),anyString(),anyString());
        var flow=new AppointmentWorkflowService(db.mongo,new ConversationHistoryService(db.mongo),failing,owned(),new AppointmentSessionService(db.source));
        step("write fails after checkpoint",()->{
            assertThrows(IllegalStateException.class,()->flow.draft(cp.id(),OWNER,cp.version(),true));
            assertEquals("RECOVERY_REQUIRED",flow.get(cp.id(),OWNER).phase()); assertEquals(0,db.active());
        });
        step("explicit recovery reuses stable identity",()->{
            var restarted=db.workflow(); var first=restarted.draft(cp.id(),OWNER,cp.version(),true);
            assertEquals(first.draftId(),restarted.draft(cp.id(),OWNER,cp.version(),true).draftId());
            assertEquals(1,db.mongo.getCollection("demo_appointment_drafts").countDocuments()); assertEquals(0,db.active());
        });
    }

    @Test void foreignAccountCannotResumeOrMutateExistingWorkflow() {
        var cp=db.readyWorkflow(); var flow=db.workflow(); String other="22222222-2222-2222-2222-222222222222";
        step("owner prepares a draft",()->flow.draft(cp.id(),OWNER,cp.version(),true));
        step("foreign account cannot read query edit confirm or cancel",()->{
            rejected(404,()->flow.get(cp.id(),other)); rejected(404,()->flow.query(cp.id(),other));
            rejected(404,()->flow.requirements(cp.id(),other,selection(cp,"2")));
            rejected(404,()->flow.draft(cp.id(),other,cp.version(),true));
            rejected(404,()->flow.confirm(cp.id(),other,true)); rejected(404,()->flow.cancel(cp.id(),other,true));
            assertEquals("PENDING_CONFIRMATION",flow.get(cp.id(),OWNER).phase()); assertEquals(0,db.active());
        });
    }
}
