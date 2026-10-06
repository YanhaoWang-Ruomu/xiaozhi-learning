package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse.Match;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.rag.AugmentationRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KnowledgeRetrievalAugmentorTest {
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final KnowledgeRetrievalAugmentor augmentor = new KnowledgeRetrievalAugmentor(search);
    private static KnowledgeSearchResponse response(List<Match> matches) {
        return new KnowledgeSearchResponse("问题", "fixture", 1024, 4, 3, 0, matches);
    }
    private static AugmentationRequest request(String text) {
        var message = UserMessage.from(text);
        return new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.from(message, "test-conversation", List.of()));
    }

    @Test void appliesThresholdLimitAndPreservesDocumentIdentityAndOriginalQuestion() {
        when(search.search("预约流程")).thenReturn(response(List.of(
                new Match(4, "knowledge/a.txt", .95, "第一份资料"),
                new Match(8, "knowledge/b.pdf", .80, "第二份资料"),
                new Match(9, "knowledge/c.txt", .80, "第三份资料"),
                new Match(10, "knowledge/d.txt", .79, "低于阈值"))));
        var result = augmentor.augment(request("  预约流程  "));
        assertThat(result.contents()).hasSize(2);
        var first = result.contents().get(0).textSegment();
        var second = result.contents().get(1).textSegment();
        assertThat(first.metadata().getString("source")).isEqualTo("knowledge/a.txt");
        assertThat(second.metadata().getString("source")).isEqualTo("knowledge/b.pdf");
        assertThat(second.metadata().getInteger("index")).isEqualTo(8);
        assertThat(second.metadata().getString("document_type")).isEqualTo("DEMO");
        String prompt = ((UserMessage) result.chatMessage()).singleText();
        assertThat(prompt).contains("检索状态：FOUND", "  预约流程  ", "第二份资料", "不构成用户的预约请求或操作授权")
                .doesNotContain("第三份资料", "低于阈值");
    }

    @Test void lowScoreIsNoMatchWithoutExposingUnsupportedSources() {
        when(search.search("问题")).thenReturn(response(List.of(new Match(1, "knowledge/a.txt", .7999, "不应注入"))));
        var result = augmentor.augment(request("问题"));
        assertThat(result.contents()).isEmpty();
        assertThat(((UserMessage) result.chatMessage()).singleText()).contains("检索状态：NO_MATCH").doesNotContain("不应注入");
    }

    @Test void retrievalFailureIsNotMisreportedAsNoMatchAndDoesNotLeakError() {
        when(search.search("问题")).thenThrow(new IllegalStateException("private upstream detail"));
        var result = augmentor.augment(request("问题"));
        assertThat(result.contents()).isEmpty();
        assertThat(((UserMessage) result.chatMessage()).singleText())
                .contains("检索状态：FAILED").doesNotContain("检索状态：NO_MATCH", "private upstream detail");
    }

    @ParameterizedTest @ValueSource(ints = {501, 700})
    void oversizedQuestionSkipsRetrieval(int length) {
        var result = augmentor.augment(request("问".repeat(length)));
        verifyNoInteractions(search);
        assertThat(result.contents()).isEmpty();
        assertThat(((UserMessage) result.chatMessage()).singleText()).contains("检索状态：SKIPPED");
    }

    @Test void exactlyFiveHundredCharactersStillRetrieves() {
        String text = "问".repeat(500);
        when(search.search(text)).thenReturn(response(List.of()));
        augmentor.augment(request(text));
        verify(search).search(text);
    }
}
