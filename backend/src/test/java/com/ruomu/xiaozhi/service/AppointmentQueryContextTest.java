package com.ruomu.xiaozhi.service;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class AppointmentQueryContextTest {
    private final AppointmentQueryContext context=new AppointmentQueryContext();
    private String issue(String id) {
        context.begin(id); return context.issued(id,context.turn(id),"DEMO001","内科");
    }
    @Test void noTurnCannotIssueOrConsume() {
        assertNull(context.issued("a",null,"DEMO001","内科"));
        assertFalse(context.consume("a","invented","DEMO001","内科"));
    }
    @Test void validReceiptIsSingleUse() {
        String receipt=issue("a");
        assertTrue(context.consume("a",receipt,"DEMO001","内科"));
        assertFalse(context.consume("a",receipt,"DEMO001","内科"));
    }
    @Test void nextTurnInvalidatesPreviousReceipt() {
        String receipt=issue("a");context.begin("a");
        assertFalse(context.consume("a",receipt,"DEMO001","内科"));
    }
    @Test void receiptsCannotCrossConversationOrTarget() {
        String receipt=issue("a");context.begin("b");
        assertFalse(context.consume("b",receipt,"DEMO001","内科"));
        assertFalse(context.consume("a",receipt,"OTHER","内科"));
        assertFalse(context.consume("a",receipt,"DEMO001","外科"));
        assertTrue(context.consume("a",receipt,"DEMO001","内科"));
    }
    @Test void failedQueryRevokesOldReceipt() {
        String receipt=issue("a");context.failed("a",context.turn("a"));
        assertFalse(context.consume("a",receipt,"DEMO001","内科"));
    }
    @Test void staleAsyncQueryCannotIssueForNewTurnOrRevokeNewReceipt() {
        issue("a");String oldTurn=context.turn("a");
        String latest=issue("a");
        assertNull(context.issued("a",oldTurn,"DEMO001","内科"));
        context.failed("a",oldTurn);
        assertTrue(context.consume("a",latest,"DEMO001","内科"));
    }
    @Test void expiryUsesClockAndFailsClosed() {
        Clock clock=mock(Clock.class);Instant now=Instant.parse("2026-10-08T00:00:00Z");
        when(clock.instant()).thenReturn(now);
        var timed=new AppointmentQueryContext(clock);timed.begin("a");
        String receipt=timed.issued("a",timed.turn("a"),"DEMO001","内科");
        when(clock.instant()).thenReturn(now.plusSeconds(91));
        assertFalse(timed.consume("a",receipt,"DEMO001","内科"));
        assertNull(timed.turn("a"));
    }
}
