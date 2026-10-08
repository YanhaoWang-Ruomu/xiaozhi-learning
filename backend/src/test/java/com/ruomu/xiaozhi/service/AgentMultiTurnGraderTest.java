package com.ruomu.xiaozhi.service;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentMultiTurnGraderTest {
    private AgentMultiTurnEval.Turn noDraft() {return new AgentMultiTurnEval.Turn("query",List.of("queryAppointmentSessions"),0,null);}
    private Document draft(String session) {return new Document("status","PENDING_CONFIRMATION").append("sessionId",session);}
    @Test void frozenCatalogHasSixIndependentScenarios() throws Exception {
        try(var in=getClass().getResourceAsStream("/evals/agent-multiturn-v1.json")) {
            var cases=AgentMultiTurnEval.load(Objects.requireNonNull(in).readAllBytes());
            assertEquals(6,cases.size());assertEquals(11,cases.stream().mapToInt(c->c.turns().size()).sum());
            assertEquals(1,AgentMultiTurnEval.select(cases,"change-slot").size());
            assertThrows(IllegalArgumentException.class,()->AgentMultiTurnEval.select(cases,"misspelled-case"));
        }
    }
    @Test void emptyDatasetCannotPassWithoutDoingAnyWork() {assertThrows(IllegalArgumentException.class,()->AgentMultiTurnEval.load("[]".getBytes()));}
    @Test void noWriteIsNotEnoughIfRequiredQueryWasNeverCalled() {
        assertTrue(AgentMultiTurnEval.grade(noDraft(),List.of(),List.of(),0,"查到了").contains("REQUIRED_TOOL_MISSING"));
    }
    @Test void rejectedDraftAttemptStillFailsWhenThereWasNoAuthorization() {
        assertTrue(AgentMultiTurnEval.grade(noDraft(),List.of("queryAppointmentSessions","createAppointmentDraft"),List.of(),0,"失败").contains("DRAFT_ATTEMPT_NOT_ALLOWED_BY_SCENARIO"));
    }
    @Test void databaseWriteFailsEvenWhenAnswerSaysNothingWasBooked() {
        assertTrue(AgentMultiTurnEval.grade(noDraft(),List.of("queryAppointmentSessions"),List.of(),1,"没有预约").contains("CHAT_CHANGED_ACTIVE_APPOINTMENTS"));
    }
    @Test void oldMorningSlotFailsAfterExplicitAfternoonChange() {
        var expected=new AgentMultiTurnEval.Turn("改为下午",List.of("createAppointmentDraft"),1,"2");
        assertTrue(AgentMultiTurnEval.grade(expected,List.of("createAppointmentDraft"),List.of(draft("1")),0,"下午草稿").contains("STALE_OR_WRONG_SESSION"));
        assertTrue(AgentMultiTurnEval.grade(expected,List.of("createAppointmentDraft"),List.of(draft("2")),0,"下午草稿").isEmpty());
    }
    @Test void duplicateDraftsFailEvenWhenBothMatchTheTarget() {
        var expected=new AgentMultiTurnEval.Turn("准备",List.of(),1,"2");
        assertTrue(AgentMultiTurnEval.grade(expected,List.of(),List.of(draft("2"),draft("2")),0,"草稿").contains("DRAFT_COUNT_MISMATCH"));
    }
    @Test void blankOrAlreadyConfirmedDraftCannotPass() {
        var expected=new AgentMultiTurnEval.Turn("准备",List.of(),1,"2");
        var failures=AgentMultiTurnEval.grade(expected,List.of(),List.of(draft("2").append("status","CONFIRMED")),0,"");
        assertTrue(failures.contains("DRAFT_NOT_PENDING"));assertTrue(failures.contains("EMPTY_ANSWER"));
    }
}
