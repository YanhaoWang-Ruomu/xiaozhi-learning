package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.rag.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class CorrectiveKnowledgeServiceTest {
    private static final Match GOOD=new Match(1,"knowledge/guide.txt",.91,"轮椅借用点在青竹服务台。预约草稿不占号。");
    private static final String YES="{\"decision\":\"SUFFICIENT\",\"evidence\":[{\"index\":1,\"quote\":\"轮椅借用点在青竹服务台。\"}],\"rewrite\":\"\"}";
    private static final String NO="{\"decision\":\"INSUFFICIENT\",\"evidence\":[],\"rewrite\":\"\"}";
    private final List<String> queries=new ArrayList<>();
    private final List<String> prompts=new ArrayList<>();
    private CorrectiveKnowledgeService service(List<List<Match>> results,String... judgments) {
        var answers=new ArrayDeque<>(List.of(judgments));
        return new CorrectiveKnowledgeService(q->{queries.add(q);return results.get(queries.size()-1);},
                p->{prompts.add(p);return answers.remove();});
    }
    @Test void sufficientQuotesPreserveIdentityWithoutExposingUnselectedText() {
        var result=service(List.of(List.of(GOOD)),YES).search("轮椅在哪里借用？");
        assertEquals("FOUND",result.status());assertEquals(1,result.attempts().size());
        assertEquals(GOOD.source(),result.accepted().get(0).source());
        assertEquals("轮椅借用点在青竹服务台。",result.accepted().get(0).text());
        assertEquals("",result.fallbackReply());
    }
    @Test void insufficientEvidenceRewritesOnceThenRechecksOriginalQuestion() {
        String retry=NO.replace("\"rewrite\":\"\"","\"rewrite\":\"轮椅借用点位置\"");
        var result=service(List.of(List.of(),List.of(GOOD)),retry,YES).search("轮椅在哪领？");
        assertEquals("CORRECTED",result.status());assertEquals(List.of("轮椅在哪领？","轮椅借用点位置"),queries);
        assertTrue(prompts.get(1).contains("轮椅在哪领？"));assertTrue(prompts.get(1).contains("\"mayRewrite\":false"));
    }
    @Test void secondInsufficientStopsWithoutThirdAttempt() {
        var result=service(List.of(List.of(),List.of()),NO.replace("\"rewrite\":\"\"","\"rewrite\":\"轮椅借用位置\""),NO).search("轮椅在哪借？");
        assertEquals("INSUFFICIENT",result.status());assertEquals(2,queries.size());assertTrue(result.accepted().isEmpty());
        assertTrue(result.fallbackReply().contains("不足"));
    }
    @Test void conflictStopsAndDoesNotPublishContradictorySources() {
        var other=new Match(2,"other",.9,"轮椅借用点在橙帆服务台。");
        String conflict="{\"decision\":\"CONFLICT\",\"evidence\":[{\"index\":1,\"quote\":\"轮椅借用点在青竹服务台。\"},{\"index\":2,\"quote\":\"轮椅借用点在橙帆服务台。\"}],\"rewrite\":\"\"}";
        var result=service(List.of(List.of(GOOD,other)),conflict).search("轮椅借用点在哪？");
        assertEquals("CONFLICT",result.status());assertEquals(1,queries.size());assertTrue(result.accepted().isEmpty());
    }
    @Test void retrievalFailureDoesNotBecomeNoEvidenceAndDoesNotRetry() {
        var s=new CorrectiveKnowledgeService(q->{queries.add(q);throw new IllegalStateException("private db");},p->{fail();return "";});
        var r=s.search("轮椅在哪里？");assertEquals("FAILED",r.status());assertEquals("RETRIEVAL_FAILED",r.failureCode());
        assertEquals(1,queries.size());assertFalse(r.fallbackReply().contains("private"));
    }
    @Test void secondRetrievalFailureRetainsFirstAttemptWithoutPublishingIt() {
        var s=new CorrectiveKnowledgeService(q->{queries.add(q);if(queries.size()>1)throw new IllegalStateException();return List.of(GOOD);},
                p->NO.replace("\"rewrite\":\"\"","\"rewrite\":\"轮椅借用位置\""));
        var r=s.search("轮椅在哪？");assertEquals("FAILED",r.status());assertEquals(2,r.attempts().size());assertTrue(r.accepted().isEmpty());
    }
    @Test void judgeFailureClosesWithoutExposingPrivateMessage() {
        var s=new CorrectiveKnowledgeService(q->List.of(GOOD),p->{throw new IllegalStateException("private model");});
        var r=s.search("轮椅在哪里？");assertEquals("EVIDENCE_CHECK_FAILED",r.failureCode());assertFalse(r.toString().contains("private model"));
    }
    @Test void fabricatedQuoteOrUnknownIndexCannotPass() {
        for(String bad:List.of(YES.replace("青竹","虚构地点"),YES.replace("\"index\":1","\"index\":99"),YES.replace("\"index\":1","\"index\":4294967297"))) {
            var r=new CorrectiveKnowledgeService(q->List.of(GOOD),p->bad).search("轮椅在哪里？");
            assertEquals("FAILED",r.status());assertTrue(r.accepted().isEmpty());
        }
    }
    @Test void malformedSchemaScoresAndEmptyEvidenceFailClosed() {
        for(String bad:List.of("not-json","{}",YES.replace("SUFFICIENT","MAYBE"),YES.replace("[{\"index\":1,\"quote\":\"轮椅借用点在青竹服务台。\"}]","[]"),YES.replace("\"rewrite\":\"\"","\"rewrite\":null"))) {
            assertEquals("FAILED",new CorrectiveKnowledgeService(q->List.of(GOOD),p->bad).search("轮椅在哪里？").status());
        }
    }
    @Test void changedAnchorsAndNegationCannotDriveRequery() {
        assertFalse(CorrectiveKnowledgeService.safeRewrite("DEMO001医院9点","DEMO002医院10点"));
        assertFalse(CorrectiveKnowledgeService.safeRewrite("DEMO001医院在哪里","医院位置"));
        assertFalse(CorrectiveKnowledgeService.safeRewrite("不需要预约吗","预约要求"));
        assertFalse(CorrectiveKnowledgeService.safeRewrite("服务台在哪","服务台在哪"));
        assertTrue(CorrectiveKnowledgeService.safeRewrite("DEMO001轮椅在哪","DEMO001轮椅借用点位置"));
    }
    @Test void rejectedRewriteIsInsufficientNotInfrastructureFailure() {
        var r=service(List.of(List.of()),NO.replace("\"rewrite\":\"\"","\"rewrite\":\"DEMO002轮椅\"")).search("DEMO001轮椅在哪");
        assertEquals("INSUFFICIENT",r.status());assertEquals("REWRITE_REJECTED",r.failureCode());assertEquals(1,queries.size());
    }
    @Test void noThirdRewriteAndNoConflictWithoutTwoCitations() {
        var r=service(List.of(List.of(),List.of()),NO.replace("\"rewrite\":\"\"","\"rewrite\":\"轮椅借用点\""),NO.replace("\"rewrite\":\"\"","\"rewrite\":\"再查\"")).search("轮椅在哪");
        assertEquals("FAILED",r.status());assertEquals(2,queries.size());
        assertEquals("FAILED",new CorrectiveKnowledgeService(q->List.of(GOOD),p->YES.replace("SUFFICIENT","CONFLICT")).search("轮椅在哪").status());
    }
    @Test void excessiveOrDuplicateCandidatesAreRejectedBeforeJudge() {
        var s=new CorrectiveKnowledgeService(q->List.of(GOOD,GOOD),p->{fail();return "";});
        assertEquals("RETRIEVAL_FAILED",s.search("轮椅在哪").failureCode());
    }
    @Test void validatesInputAndKeepsMedicalAndGeneralQuestionsOutsideScope() {
        var s=service(List.of(List.of()),NO);
        assertThrows(IllegalArgumentException.class,()->s.search(" "));
        assertThrows(IllegalArgumentException.class,()->s.search("问".repeat(501)));
        assertTrue(s.applies("医院服务台在哪里"));
        assertFalse(s.applies("你好"));assertFalse(s.applies("胸痛去医院"));assertFalse(s.applies("医院药物剂量"));
    }
    private static AugmentationRequest request(String raw) {
        var user=UserMessage.from(raw);
        return new AugmentationRequest(user,dev.langchain4j.rag.query.Metadata.from(user,"c",List.of()));
    }
    @Test void augmentorPassesValidatedQuotesAndReportsCorrectedStatus() {
        var vector=mock(KnowledgeSearchService.class);var rag=new KnowledgeRetrievalAugmentor(vector);
        rag.setCorrective(service(List.of(List.of(),List.of(GOOD)),NO.replace("\"rewrite\":\"\"","\"rewrite\":\"轮椅借用位置\""),YES));
        var r=rag.augment(request("轮椅在哪？"));assertEquals(1,r.contents().size());
        assertTrue(((UserMessage)r.chatMessage()).singleText().contains("检索状态：CORRECTED"));
        verifyNoInteractions(vector);
    }
    @Test void insufficientContextForcesSameSafeSyncAndStreamAnswerWithoutModel() {
        var context=new AppointmentQueryContext();var rag=new KnowledgeRetrievalAugmentor(mock(KnowledgeSearchService.class));
        rag.setQueryContext(context);rag.setCorrective(service(List.of(List.of()),NO));
        var augmented=rag.augment(request("医院服务台位置在哪？"));
        assertTrue(augmented.contents().isEmpty());
        var chat=ChatRequest.builder().messages(augmented.chatMessage()).build();
        var sync=mock(ChatLanguageModel.class);var streaming=mock(StreamingChatLanguageModel.class);
        var response=VerifiedBookingModels.sync(sync,context).chat(chat);
        assertTrue(response.aiMessage().text().contains("不足"));verifyNoInteractions(sync);
        var handler=mock(StreamingChatResponseHandler.class);
        VerifiedBookingModels.streaming(streaming,context).chat(chat,handler);
        verify(handler).onPartialResponse(response.aiMessage().text());verify(handler).onCompleteResponse(any());verifyNoInteractions(streaming);
    }
    @Test void replyIsBoundToCurrentTurnAndCannotLeakToAnotherConversation() {
        var context=new AppointmentQueryContext();context.begin("a","医院在哪？");String old=context.turn("a");
        context.evidenceReply("a",old,"clarify");context.begin("b","医院在哪？");
        assertNull(context.evidenceReplyFor(context.turn("b")));
        context.begin("a","你好");context.evidenceReply("a",old,"late result");
        assertNull(context.evidenceReplyFor(old));assertNull(context.evidenceReplyFor(context.turn("a")));
    }
    @Test void bookingFlowBypassesCorrectiveChecker() {
        var vector=mock(KnowledgeSearchService.class);
        when(vector.search(anyString())).thenReturn(new com.ruomu.xiaozhi.dto.KnowledgeSearchResponse("q","test",1,0,0,0,List.of()));
        var rag=new KnowledgeRetrievalAugmentor(vector);rag.setQueryContext(new AppointmentQueryContext());
        rag.setCorrective(new CorrectiveKnowledgeService(q->{fail("booking must not enter checker");return List.of();},p->{fail();return "";}));
        rag.augment(request("预约DEMO001内科明天测试医生上午"));
        verifyNoInteractions(vector);
    }

    @Test void recallUsesValidatedCorpusIdentityWithoutConfusingShortQuotesWithMissingRetrieval() {
        var c=new com.ruomu.xiaozhi.eval.RetrievalEval.Case("R01","location","轮椅在哪",List.of(new com.ruomu.xiaozhi.eval.RetrievalEval.Evidence(GOOD.source(),"轮椅借用点在青竹服务台。")));
        var corpus=List.of(new com.ruomu.xiaozhi.dto.KnowledgePreviewResponse.Chunk(1,GOOD.source(),20,GOOD.text()));
        assertEquals(1,CorrectiveRagEval.recall(c,List.of(new Match(1,GOOD.source(),.9,"青竹服务台")),corpus));
        assertEquals(0,CorrectiveRagEval.recall(c,List.of(new Match(1,"wrong-source",.9,"青竹服务台")),corpus));
        assertEquals(0,CorrectiveRagEval.recall(c,List.of(new Match(2,GOOD.source(),.9,"青竹服务台")),corpus));
    }
    @Test void featureCanBeDisabledWithoutCallingCloudChecker() {
        var model=mock(dev.langchain4j.community.model.dashscope.QwenChatModel.class);
        var s=new CorrectiveKnowledgeService(mock(HybridKnowledgeService.class),model,false);
        assertFalse(s.applies("医院服务台在哪里？"));verifyNoInteractions(model);
    }

    @Test void observedEllipsisInsideClaimedVerbatimQuoteFailsClosed() {
        String condensed=YES.replace("轮椅借用点在青竹服务台。","轮椅借用点……青竹服务台。");
        var r=new CorrectiveKnowledgeService(q->List.of(GOOD),p->condensed).search("轮椅在哪里");
        assertEquals("FAILED",r.status());assertEquals("EVIDENCE_CHECK_FAILED",r.failureCode());
        assertTrue(r.accepted().isEmpty());
    }
    @Test void releaseGateRejectsRecallRegressionMissingRunsErrorsAndMoreFalseAccepts() {
        assertFalse(CorrectiveRagEval.releaseGate(.975,.825,0,0,0,24,24));
        assertFalse(CorrectiveRagEval.releaseGate(.975,.925,0,0,1,24,24));
        assertFalse(CorrectiveRagEval.releaseGate(.975,1,0,0,0,24,23));
        assertFalse(CorrectiveRagEval.releaseGate(.975,1,0,1,0,24,24));
        assertTrue(CorrectiveRagEval.releaseGate(.975,1,0,0,0,24,24));
    }
}
