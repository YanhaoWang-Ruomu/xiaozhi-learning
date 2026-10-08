package com.ruomu.xiaozhi.service;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class EvidenceBoundAnswerTest {
    private static Match m(int i,String source,String text){return new Match(i,source,.9,text);}
    private final List<Match> evidence=List.of(m(1,"guide.txt","服务台在一层。开放时间为08:00。"));
    @Test void locationAndTimeAreLiteralWithSource(){
        String r=EvidenceBoundAnswer.render(List.of(m(1,"guide.txt","服务台在一层。")),evidence);
        assertTrue(r.contains("guide.txt"));assertFalse(r.contains("08:00"));assertTrue(r.contains("不能用于判断个人预约是否成功"));
    }
    @Test void inventedFloorRejected(){assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(m(1,"guide.txt","服务台在二层。")),evidence));}
    @Test void wrongSourceRejected(){assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(m(1,"other.txt","服务台在一层。")),evidence));}
    @Test void wrongIndexRejected(){assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(m(2,"guide.txt","服务台在一层。")),evidence));}
    @Test void noEvidenceRejected(){assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(),evidence));}
    @Test void duplicateEvidenceRejected(){var q=m(1,"guide.txt","服务台在一层。");assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(q,q),evidence));}
    @Test void ellipsisOrModifiedTimeRejected(){for(String q:List.of("服务台……一层。","开放时间为09:00。"))assertThrows(IllegalArgumentException.class,()->EvidenceBoundAnswer.render(List.of(m(1,"guide.txt",q)),evidence));}
    @Test void successfulCorrectiveCanProduceExtractiveReplyWithoutAnotherGenerator(){
        var s=new CorrectiveKnowledgeService(q->evidence,p->"{\"decision\":\"SUFFICIENT\",\"evidence\":[{\"index\":1,\"quote\":\"服务台在一层。\"}],\"rewrite\":\"\"}");
        s.setExtractive(true);var r=s.search("服务台在哪？");assertEquals("FOUND",r.status());assertTrue(r.fallbackReply().contains("引用：服务台在一层。"));
    }

    @Test void indexOnlySelectionCopiesServerTextWithoutQuoteGeneration(){
        var service=new CorrectiveKnowledgeService(q->evidence,p->"{\"decision\":\"SUFFICIENT\",\"evidence\":[{\"index\":1}],\"rewrite\":\"\"}");
        service.setExtractive(true);var result=service.search("服务台位置和开放时间？");
        assertEquals(evidence.get(0).text(),result.accepted().get(0).text());assertTrue(result.fallbackReply().contains("08:00"));
    }
    @Test void indexOnlyUnknownEvidenceFailsClosed(){
        var service=new CorrectiveKnowledgeService(q->evidence,p->"{\"decision\":\"SUFFICIENT\",\"evidence\":[{\"index\":9}],\"rewrite\":\"\"}");
        assertEquals("FAILED",service.search("服务台在哪里").status());
    }
}
