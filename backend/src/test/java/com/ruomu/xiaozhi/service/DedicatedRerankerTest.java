package com.ruomu.xiaozhi.service;
import org.junit.jupiter.api.Test;import java.util.*;import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class DedicatedRerankerTest {
    private static final List<HybridKnowledgeService.Candidate> C=List.of(
        new HybridKnowledgeService.Candidate(12,"a.txt","alpha",.9,1,.02,.8),
        new HybridKnowledgeService.Candidate(7,"b.txt","beta",.8,2,.03,.7));
    @Test void mapsInputPositionBackToStableSourceIdentity(){
        var r=new DedicatedReranker("qwen3-rerank",body->"{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}").rerank("q",C);
        assertEquals(7,r.get(0).index());assertEquals("b.txt",r.get(0).source());assertEquals(.8,r.get(0).dense());
    }
    @Test void nativeApiUsesInputAndOutputEnvelopes(){
        var body=new AtomicReference<String>();
        var s=new DedicatedReranker("gte-rerank-v2",b->{body.set(b);return "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":0.9}]}}";});
        assertEquals(7,s.rerank("q",C).get(0).index());assertTrue(body.get().contains("\"input\""));
    }
    @Test void duplicateIndicesRejected(){bad("[{\"index\":0,\"relevance_score\":0.1},{\"index\":0,\"relevance_score\":0.9}]");}
    @Test void outOfRangeAndOverflowRejected(){for(String i:List.of("-1","2","4294967296"))bad("[{\"index\":0,\"relevance_score\":0.1},{\"index\":"+i+",\"relevance_score\":0.9}]");}
    @Test void missingScoresRejected(){bad("[{\"index\":0,\"relevance_score\":0.1}]");}
    @Test void nonNumericOutOfRangeScoresRejected(){for(String s:List.of("\"0.9\"","-0.1","1.1","null"))bad("[{\"index\":0,\"relevance_score\":0.1},{\"index\":1,\"relevance_score\":"+s+"}]");}
    @Test void failuresPropagateWithoutFakeFallback(){assertThrows(IllegalStateException.class,()->new DedicatedReranker("qwen3-rerank",b->{throw new IllegalStateException("HTTP");}).rerank("q",C));}
    @Test void emptyCandidatesMakeNoHttpCall(){assertTrue(new DedicatedReranker("qwen3-rerank",b->{fail();return "";}).rerank("q",List.of()).isEmpty());}
    @Test void endpointCannotRedirectKeyToUnrelatedHost(){
        for(String url:List.of("http://dashscope.aliyuncs.com/x","https://evil.test/x","https://dashscope.aliyuncs.com.evil.test/x","https://user@dashscope.aliyuncs.com/x"))
            assertThrows(IllegalArgumentException.class,()->DedicatedReranker.validateEndpoint(url));
        DedicatedReranker.validateEndpoint("https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank");
    }
    @Test void validatesInputBudget(){assertThrows(IllegalArgumentException.class,()->new DedicatedReranker("qwen3-rerank",b->{fail();return "";}).rerank("x".repeat(501),C));}
    private void bad(String array){assertThrows(RuntimeException.class,()->new DedicatedReranker("qwen3-rerank",b->"{\"results\":"+array+"}").rerank("q",C));}
}
