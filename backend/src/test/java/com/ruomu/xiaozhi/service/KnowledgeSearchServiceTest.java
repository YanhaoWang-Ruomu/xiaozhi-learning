package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse.Chunk;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.server.ResponseStatusException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Exercises the real service while replacing only embedding and Pinecone network boundaries. */
class KnowledgeSearchServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final EmbeddingModel embeddings = mock(EmbeddingModel.class);
    private final PineconeClient pinecone = mock(PineconeClient.class);
    private final Map<String, JsonNode> records = new LinkedHashMap<>();
    private KnowledgeSearchService service;
    private ArrayNode results;
    private String namespace;

    @BeforeEach void fixture() {
        var documents = mock(KnowledgeDocumentService.class);
        when(documents.preview()).thenReturn(new KnowledgePreviewResponse("fixture", 2, 500, 50, List.of(
                new Chunk(0, "knowledge/guide.txt", 4, "预约说明"),
                new Chunk(1, "knowledge/visit.pdf", 4, "就诊说明"))));
        float[] vector = new float[PineconeClient.DIMENSION];
        vector[0] = 1;
        when(embeddings.embedAll(anyList())).thenReturn(Response.from(List.of(Embedding.from(vector), Embedding.from(vector))));
        when(embeddings.embed(any(TextSegment.class))).thenReturn(Response.from(Embedding.from(vector)));
        when(pinecone.fetch(anyString(), anyList())).thenAnswer(call -> new LinkedHashMap<>(records));
        doAnswer(call -> {
            namespace = call.getArgument(0);
            List<Map<String, Object>> vectors = call.getArgument(1);
            for (var item : vectors) records.put((String) item.get("id"), json.valueToTree(item));
            return null;
        }).when(pinecone).upsert(anyString(), anyList());
        service = new KnowledgeSearchService(documents, embeddings, pinecone);
        assertThat(service.sync().status()).isEqualTo("SYNCED");
        results = json.createArrayNode();
        results.add(((ObjectNode) records.get("chunk-0")).deepCopy().put("score", .6));
        results.add(((ObjectNode) records.get("chunk-1")).deepCopy().put("score", .9));
        when(pinecone.query(anyString(), anyString(), any(float[].class), anyInt())).thenAnswer(call -> results);
        clearInvocations(embeddings, pinecone);
    }

    private void fails(int code) {
        var error = assertThrows(ResponseStatusException.class, () -> service.search("预约流程"));
        assertThat(error.getStatusCode().value()).isEqualTo(code);
    }

    @Test void queryUsesCurrentRevisionAndReturnsSortedNormalizedScoresWithExactSources() {
        var response = service.search("  预约流程  ");
        assertThat(response.query()).isEqualTo("预约流程");
        assertThat(response.matches()).hasSize(2);
        assertThat(response.matches().get(0).source()).isEqualTo("knowledge/visit.pdf");
        assertThat(response.matches().get(0).text()).isEqualTo("就诊说明");
        assertThat(response.matches().get(0).score()).isEqualTo(.95);
        assertThat(response.matches().get(1).score()).isEqualTo(.8);
        verify(pinecone).query(eq(namespace), eq(namespace.substring("demo-".length())), any(float[].class), eq(2));
    }

    @ParameterizedTest @ValueSource(strings = {"source", "text", "revision", "model", "document_type"})
    void rejectsChangedSourceTextRevisionModelOrDocumentType(String field) {
        ((ObjectNode) results.get(0).get("metadata")).put(field, "changed");
        fails(502);
    }

    @Test void rejectsWrongChunkIndex() {
        ((ObjectNode) results.get(0).get("metadata")).put("index", 99);
        fails(502);
    }

    @Test void rejectsDuplicateReturnedChunkIds() {
        results.set(1, results.get(0).deepCopy());
        fails(502);
    }

    @ParameterizedTest @ValueSource(doubles = {-1.1, 1.1, Double.NaN, Double.POSITIVE_INFINITY})
    void rejectsInvalidScores(double score) {
        ((ObjectNode) results.get(0)).put("score", score);
        fails(502);
    }

    @Test void incompleteIndexDoesNotMasqueradeAsNoMatch() {
        results.remove(1);
        fails(503);
    }

    @Test void missingStoredChunkBlocksQueryBeforeEmbedding() {
        records.remove("chunk-0");
        fails(503);
        verifyNoInteractions(embeddings);
        verify(pinecone, never()).query(anyString(), anyString(), any(float[].class), anyInt());
    }

    @Test void zeroStoredVectorIsNotAcceptedAsValidIndexedDocument() {
        ((ObjectNode) records.get("chunk-0")).set("values", json.valueToTree(new float[PineconeClient.DIMENSION]));
        fails(503);
        verifyNoInteractions(embeddings);
    }

    @Test void invalidQueryVectorIsRejectedBeforeRemoteQuery() {
        when(embeddings.embed(any(TextSegment.class))).thenReturn(Response.from(Embedding.from(new float[1024])));
        fails(502);
        verify(pinecone, never()).query(anyString(), anyString(), any(float[].class), anyInt());
    }

    @Test void repeatedSyncReusesExistingDocumentsWithoutAnotherUpsert() {
        var response = service.sync();
        assertThat(response.uploadedChunks()).isZero();
        assertThat(response.reusedChunks()).isEqualTo(2);
        verify(pinecone, never()).upsert(anyString(), anyList());
        verifyNoInteractions(embeddings);
    }
}
