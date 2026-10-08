package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.AppointmentSessionResponse;
import com.ruomu.xiaozhi.tool.AppointmentTools;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentToolFailureTest {
    private final AppointmentSessionService sessions = mock(AppointmentSessionService.class);
    private final OwnedAppointmentService drafts = mock(OwnedAppointmentService.class);
    private final AppointmentQueryContext queries = new AppointmentQueryContext();
    private String receipt() { queries.begin("conversation","请为我准备DEMO001内科2026-10-09演示医生上午草稿"); return queries.issued("conversation",queries.turn("conversation"),"DEMO001","内科"); }
    private final AppointmentTools tools = new AppointmentTools(mock(AppointmentRuleService.class),
            drafts, mock(AppointmentScheduleService.class), sessions, queries);

    @Test void infrastructureFailureIsUnknownAndDoesNotExposeExceptionDetails() {
        when(sessions.findSessions("DEMO001","内科")).thenThrow(new IllegalStateException("secret-db-host"));
        var result = tools.queryAppointmentSessions("DEMO001","内科","conversation");
        assertEquals("QUERY_FAILED", result.get("status"));
        assertEquals("UNKNOWN", result.get("availability"));
        assertEquals(false, result.get("retryInCurrentTurn"));
        assertFalse(result.toString().contains("secret-db-host"));
        assertFalse(result.containsKey("data"));
        verify(sessions, times(1)).findSessions("DEMO001","内科");
        verifyNoInteractions(drafts);
    }

    @Test void inputErrorStaysDistinctFromInfrastructureFailure() {
        when(sessions.findSessions("","内科")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少医院编号"));
        assertEquals("INVALID_INPUT",tools.queryAppointmentSessions("","内科","conversation").get("status"));
        verifyNoInteractions(drafts);
    }

    @Test void permissionFailureIsNotConvertedIntoAnAvailabilityAnswer() {
        when(sessions.findSessions("DEMO001","内科")).thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN));
        assertEquals(403,assertThrows(ResponseStatusException.class,
                ()->tools.queryAppointmentSessions("DEMO001","内科","conversation")).getStatusCode().value());
    }

    @Test void serviceUnavailableIsQueryFailed() {
        when(sessions.findSessions("DEMO001","内科")).thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"private reason"));
        var result=tools.queryAppointmentSessions("DEMO001","内科","conversation");
        assertEquals("QUERY_FAILED",result.get("status"));
        assertFalse(result.toString().contains("private reason"));
    }

    @Test void successfulNoDataIsPreserved() {
        var response=new AppointmentSessionResponse("NO_DATA","UNKNOWN","内科",LocalDate.of(2026,10,8),
                "Asia/Shanghai",false,List.of(),"没有配置");
        when(sessions.findSessions("UNKNOWN","内科")).thenReturn(response);
        var result=tools.queryAppointmentSessions("UNKNOWN","内科","conversation");
        assertEquals("NO_DATA",result.get("status"));
        assertSame(response,result.get("data"));
        verifyNoInteractions(drafts);
    }

    @Test void failedPreWriteRecheckCannotCreateDraft() {
        when(sessions.findSessions("DEMO001","内科")).thenThrow(new IllegalStateException("database unavailable"));
        var result=tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt(),"conversation");
        assertEquals("QUERY_FAILED",result.get("status"));
        verifyNoInteractions(drafts);
    }

    @Test void missingReceiptRejectsBeforeReadOrWrite() {
        var result=tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",null,"conversation");
        assertEquals("QUERY_REQUIRED",result.get("status"));
        verifyNoInteractions(sessions,drafts);
    }

    @Test void queryReceiptFromToolIsRevokedWhenAvailabilityChanges() {
        queries.begin("conversation");available(1);
        String receipt=(String)tools.queryAppointmentSessions("DEMO001","内科","conversation").get("queryReceipt");
        assertNotNull(receipt);
        available(0);
        assertEquals("INVALID_INPUT",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        assertEquals("QUERY_REQUIRED",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        verifyNoInteractions(drafts);
    }

    private void available(long remaining) {
        var slot=new AppointmentSessionResponse.SessionItem("1",LocalDate.of(2026,10,9),"doctor","演示医生",
                "morning","上午","09:00","12:00",1,"2026-10-08T08:00:00+08:00","RELEASED",
                0,remaining,remaining,remaining,remaining>0?"AVAILABLE":"FULL",remaining>0);
        when(sessions.findSessions("DEMO001","内科")).thenReturn(new AppointmentSessionResponse("DEMO_DATA",
                "DEMO001","内科",LocalDate.of(2026,10,8),"Asia/Shanghai",true,List.of(slot),"演示"));
    }

    @Test void freshFullResultBlocksWriteEvenIfModelUsesOldSelection() {
        available(0);
        assertEquals("INVALID_INPUT",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt(),"conversation").get("status"));
        verifyNoInteractions(drafts);
    }

    @Test void writeFailureIsNotMisreportedAsReadFailureOrNoWrite() {
        available(1);
        when(drafts.createFromChat(any(),eq("conversation"))).thenThrow(new IllegalStateException("write outcome unknown"));
        assertThrows(IllegalStateException.class,()->tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt(),"conversation"));
        verify(drafts,times(1)).createFromChat(any(),eq("conversation"));
    }

    @Test void receiptCannotAuthorizeUnselectedDoctorSlotOrNegatedRequest() {
        available(1);
        for(String raw:List.of("预约DEMO001内科2026-10-09","预约DEMO001内科2026-10-09演示医生","不要预约DEMO001内科2026-10-09演示医生上午")) {
            queries.begin("conversation",raw);
            String receipt=(String)tools.queryAppointmentSessions("DEMO001","内科","conversation").get("queryReceipt");
            assertEquals("SELECTION_REQUIRED",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        }
        verifyNoInteractions(drafts);
    }
    @Test void hospitalOrDateSelectionCannotBeReplacedByModelArguments() {
        available(1);
        for(String raw:List.of("预约DEMO002内科2026-10-09演示医生上午","预约DEMO001内科2026-10-10演示医生上午")) {
            queries.begin("conversation",raw);
            String receipt=(String)tools.queryAppointmentSessions("DEMO001","内科","conversation").get("queryReceipt");
            assertEquals("SELECTION_REQUIRED",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        }
        verifyNoInteractions(drafts);
    }
    @Test void fallbackModelCannotWriteFromMissingTrustedInput() {
        available(1);queries.begin("conversation");
        String receipt=(String)tools.queryAppointmentSessions("DEMO001","内科","conversation").get("queryReceipt");
        assertEquals("SELECTION_REQUIRED",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        verifyNoInteractions(drafts);
    }
    @Test void explicitSelectionWritesOnceAndOnlyAsPendingDraft() {
        available(1);
        var saved=mock(com.ruomu.xiaozhi.dto.AppointmentDraftResponse.class);
        when(saved.status()).thenReturn("PENDING_CONFIRMATION");
        when(drafts.createFromChat(any(),eq("conversation"))).thenReturn(saved);
        String receipt=receipt();
        assertEquals("PENDING_CONFIRMATION",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        assertEquals("QUERY_REQUIRED",tools.createAppointmentDraft("DEMO001","内科","2026-10-09","1",receipt,"conversation").get("status"));
        verify(drafts,times(1)).createFromChat(argThat(r->r.visitDate().equals(LocalDate.of(2026,10,9))),eq("conversation"));
    }
}
