package com.ruomu.xiaozhi.service;
import com.ruomu.xiaozhi.dto.*;
import dev.langchain4j.community.model.dashscope.QwenChatModel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class HybridKnowledgeTest {
 private HybridKnowledgeService setup(QwenChatModel model){
  var docs=mock(KnowledgeDocumentService.class);var vector=mock(KnowledgeSearchService.class);
  when(docs.preview()).thenReturn(new KnowledgePreviewResponse("all",2,500,0,List.of(new KnowledgePreviewResponse.Chunk(0,"wheelchair",10,"轮椅借用青竹台"),new KnowledgePreviewResponse.Chunk(1,"hospital",10,"医院导诊蓝色风车"))));
  when(vector.search(anyString())).thenReturn(new KnowledgeSearchResponse("q","test",1,2,3,.8,List.of(new KnowledgeSearchResponse.Match(1,"hospital",.81,"医院导诊蓝色风车"),new KnowledgeSearchResponse.Match(0,"wheelchair",.77,"轮椅借用青竹台"))));
  return new HybridKnowledgeService(docs,vector,model,"hybrid");
 }
 @Test void lexicalEvidenceRepairsDenseRanking(){var r=setup(mock(QwenChatModel.class)).search("轮椅借用青竹台");assertEquals(0,r.ranked().get(0).index());assertTrue(r.accepted().stream().anyMatch(m->m.index()==0));assertEquals("local_feature_score",r.scoreType());}
 @Test void vectorPreservesThreshold(){var r=setup(mock(QwenChatModel.class)).search("轮椅","vector");assertEquals(1,r.accepted().size());assertEquals(1,r.accepted().get(0).index());}
 @Test void malformedLlmResultFallsBackExplicitly(){var model=mock(QwenChatModel.class);when(model.chat(anyString())).thenReturn("[{\"index\":0,\"relevance\":9}]");var r=setup(model).search("轮椅","llm");assertEquals("LLM_RERANK_FAILED_LOCAL_FALLBACK",r.fallback());assertEquals("local_feature_score",r.scoreType());}
 @Test void llmScoreUsesOwnThreshold(){var model=mock(QwenChatModel.class);when(model.chat(anyString())).thenReturn("[{\"index\":0,\"relevance\":0.7},{\"index\":1,\"relevance\":0.1}]");var r=setup(model).search("轮椅","llm");assertEquals("llm_relevance",r.scoreType());assertEquals(0,r.accepted().get(0).index());}
 @Test void validatesModeAndQuery(){var s=setup(mock(QwenChatModel.class));assertThrows(IllegalArgumentException.class,()->s.search("","hybrid"));assertThrows(IllegalArgumentException.class,()->s.search("轮椅","bad"));}
 @Test void classifiesProviderFailureWithoutLeakingMessage(){assertEquals("PROVIDER_ACCOUNT_UNAVAILABLE",HybridKnowledgeService.classifyFailure(new IllegalStateException(new RuntimeException("Arrearage private provider payload"))));}
 @Test void tokenizerNormalizesCaseAndUsesHanBigrams(){assertEquals(List.of("轮椅","椅借","借用","demo001"),HybridKnowledgeService.terms("轮椅借用 DEMO001"));}

 @Test void dedicatedModeUsesSeparateScoreAndPreservesIdentity(){
  var s=setup(mock(QwenChatModel.class));s.setDedicated(new DedicatedReranker("qwen3-rerank",b->"{\"results\":[{\"index\":0,\"relevance_score\":0.01},{\"index\":1,\"relevance_score\":0.99}]}"));
  var result=s.search("轮椅","dedicated");assertEquals("dedicated_relevance",result.scoreType());assertEquals("",result.fallback());
  assertTrue(result.accepted().stream().allMatch(m->m.source().equals(m.index()==0?"wheelchair":"hospital")));
 }
 @Test void dedicatedFailureDoesNotSilentlyReturnLocalRanking(){
  var s=setup(mock(QwenChatModel.class));s.setDedicated(new DedicatedReranker("qwen3-rerank",b->{throw new IllegalStateException("unavailable");}));
  assertThrows(IllegalStateException.class,()->s.search("轮椅","dedicated"));
 }
 @Test void missingDedicatedClientFailsExplicitly(){assertThrows(IllegalStateException.class,()->setup(mock(QwenChatModel.class)).search("轮椅","dedicated"));}
}
