package com.ruomu.examples.agentic;
import org.junit.jupiter.api.Test;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static com.ruomu.examples.agentic.BookingWorkflow.*;

class BookingWorkflowTest {
    @Test void explicitAvailableSelectionMatchesCustomFlow(){
        var w=new BookingWorkflow(id->new Facts(id,"AVAILABLE",2));var r=new Request("afternoon",true,false);
        assertEquals(w.custom(r),w.official(r));assertEquals("PENDING_CONFIRMATION",w.official(r).status());
    }
    @Test void missingSelectionMakesNoQuery(){
        var w=new BookingWorkflow(id->{fail();return null;});var r=new Request("",false,false);
        assertEquals(w.custom(r),w.official(r));assertEquals(0,w.queries());
    }
    @Test void chatConfirmationCannotWrite(){
        var w=new BookingWorkflow(id->{fail();return null;});var r=new Request("morning",true,true);
        assertEquals("HUMAN_CONFIRMATION_REQUIRED",w.official(r).status());assertEquals(w.custom(r),w.official(r));assertEquals(0,w.queries());
    }
    @Test void fullOrUnreleasedSlotCannotPrepare(){
        for(String status:new String[]{"FULL","NOT_RELEASED","NO_SCHEDULE"}){
            var w=new BookingWorkflow(id->new Facts(id,status,0));var r=new Request("morning",true,false);
            assertEquals(w.custom(r),w.official(r));assertEquals("UNAVAILABLE",w.official(r).status());
        }
    }
    @Test void toolFailureIsDistinctFromFull(){
        var w=new BookingWorkflow(id->{throw new IllegalStateException("private");});var r=new Request("morning",true,false);
        assertEquals("QUERY_FAILED",w.official(r).status());assertEquals(w.custom(r),w.official(r));
    }
    @Test void resultForAnotherSlotRejected(){
        var w=new BookingWorkflow(id->new Facts("wrong","AVAILABLE",2));var r=new Request("morning",true,false);
        assertEquals("MISMATCH",w.official(r).status());assertEquals(w.custom(r),w.official(r));
    }
    @Test void changedMindFreshQueryHasNoOldScopeLeak(){
        var w=new BookingWorkflow(id->new Facts(id,"AVAILABLE",2));
        assertEquals("morning",w.official(new Request("morning",true,false)).sessionId());
        assertEquals("afternoon",w.official(new Request("afternoon",true,false)).sessionId());assertEquals(2,w.queries());
    }
    @Test void retryAfterFailureRequeriesAndNeverCommits(){
        var n=new AtomicInteger();var w=new BookingWorkflow(id->{if(n.getAndIncrement()==0)throw new IllegalStateException();return new Facts(id,"AVAILABLE",1);});
        var r=new Request("afternoon",true,false);
        assertEquals("QUERY_FAILED",w.official(r).status());var next=w.official(r);
        assertEquals("PENDING_CONFIRMATION",next.status());assertEquals(0,next.appointmentWrites());assertEquals(2,w.queries());
    }
    @Test void zeroCapacityEvenWhenAvailableFailsClosed(){
        var w=new BookingWorkflow(id->new Facts(id,"AVAILABLE",0));
        assertEquals("UNAVAILABLE",w.official(new Request("morning",true,false)).status());
    }
}
