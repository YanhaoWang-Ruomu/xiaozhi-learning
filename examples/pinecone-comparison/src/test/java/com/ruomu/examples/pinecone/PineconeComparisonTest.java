package com.ruomu.examples.pinecone;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.pinecone.PineconeEmbeddingStore;
import io.pinecone.clients.Index;
import io.pinecone.clients.Pinecone;
import io.pinecone.unsigned_indices_model.QueryResponseWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.ScoredVectorWithUnsignedIndices;
import io.pinecone.unsigned_indices_model.VectorWithUnsignedIndices;
import org.junit.jupiter.api.*;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedConstruction;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

/** 真实PineconeEmbeddingStore + 被替换的SDK网络边界；不读取环境变量、不接触云索引。 */
class PineconeComparisonTest {
    private static final String NS="lab-only-v1", REV="fixture-v1", INDEX="offline-fixture-index";
    private final Map<String,KnowledgeContract.Chunk> manifest = Map.of(
        "chunk-0",new KnowledgeContract.Chunk(0,"knowledge/appointment-guide.txt","DEMO requires manual confirmation"),
        "chunk-1",new KnowledgeContract.Chunk(1,"knowledge/hospital-info.txt","DEMO hospital is fictional"));
    private Index index;
    private MockedConstruction<Pinecone> clients;
    private PineconeEmbeddingStore store;
    private final ObjectMapper mapper=new ObjectMapper();
    private float[] vector(float cosine) {
        float[] values=new float[1024];values[0]=cosine;values[1]=(float)Math.sqrt(1-cosine*cosine);return values;
    }
    private Struct metadata(String id, String revision) {
        var c=manifest.get(id);
        return Struct.newBuilder().putFields("text",Value.newBuilder().setStringValue(c.text()).build())
            .putFields("source",Value.newBuilder().setStringValue(c.source()).build())
            .putFields("index",Value.newBuilder().setNumberValue(c.index()).build())
            .putFields("revision",Value.newBuilder().setStringValue(revision).build())
            .putFields("model",Value.newBuilder().setStringValue(KnowledgeContract.MODEL).build())
            .putFields("document_type",Value.newBuilder().setStringValue("DEMO").build()).build();
    }
    private ScoredVectorWithUnsignedIndices hit(String id, float cosine, String revision) {
        var value=mock(ScoredVectorWithUnsignedIndices.class);
        when(value.getId()).thenReturn(id);
        when(value.getMetadata()).thenReturn(metadata(id,revision));
        when(value.getValuesList()).thenReturn(Embedding.from(vector(cosine)).vectorAsList());
        return value;
    }
    private void response(List<ScoredVectorWithUnsignedIndices> matches) {
        var response=mock(QueryResponseWithUnsignedIndices.class);
        when(response.getMatchesList()).thenReturn(matches);
        when(index.queryByVector(anyInt(),anyList(),anyString(),any(Struct.class),eq(true),eq(true))).thenReturn(response);
    }
    @BeforeEach void setup() {
        index=mock(Index.class);
        // 在构造Pinecone时拦截，不会执行SDK真实构造或任何网络调用。
        clients=mockConstruction(Pinecone.class,(client,context)->when(client.getIndexConnection(INDEX)).thenReturn(index));
        store=PineconeStoreFactory.create("offline-placeholder-not-a-secret",INDEX,NS);
        response(List.of(hit("chunk-1",0.4f,REV),hit("chunk-0",0.8f,REV)));
    }
    @AfterEach void close() { clients.close(); }

    @Test void realAdapterAndRestContractAgreeOnScoresAndSources() {
        var result=store.search(KnowledgeContract.query(vector(1),REV,2));
        var converted=KnowledgeContract.fromAdapter(result,manifest,REV);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(var entry : List.of(Map.entry("chunk-1",0.4),Map.entry("chunk-0",0.8))) {
            var c=manifest.get(entry.getKey());
            rows.add(Map.of("id",entry.getKey(),"score",entry.getValue(),"metadata",Map.of(
                "index",c.index(),"source",c.source(),"text",c.text(),"revision",REV,"model",KnowledgeContract.MODEL,"document_type","DEMO")));
        }
        var rest=KnowledgeContract.fromRest(mapper.valueToTree(rows),manifest,REV);
        for(int i=0;i<2;i++) {
            assertEquals(rest.get(i).id(),converted.get(i).id());
            assertEquals(rest.get(i).source(),converted.get(i).source());
            assertEquals(rest.get(i).text(),converted.get(i).text());
            assertEquals(rest.get(i).index(),converted.get(i).index());
            assertEquals(rest.get(i).score(),converted.get(i).score(),1e-6);
        }
        assertEquals(0.9,converted.get(0).score(),1e-6);
        assertEquals(0.7,converted.get(1).score(),1e-6);
        System.out.println("PINECONE_CONTRACT_COMPARISON_OK scores=0.9,0.7 sources=2");
    }
    @Test void forwardsNamespaceRevisionAndRequestsValues() {
        store.search(KnowledgeContract.query(vector(1),REV,2));
        var filter=ArgumentCaptor.forClass(Struct.class);
        verify(index).queryByVector(eq(2),eq(Embedding.from(vector(1)).vectorAsList()),eq(NS),filter.capture(),eq(true),eq(true));
        assertEquals(REV,filter.getValue().getFieldsOrThrow("revision").getStructValue().getFieldsOrThrow("$eq").getStringValue());
        verify(clients.constructed().get(0)).getIndexConnection(INDEX);
        verifyNoMoreInteractions(clients.constructed().get(0)); // 未尝试创建/列举/删除索引。
    }
    @Test void thresholdIsNormalizedAndMissingValuesAreNotEquivalentToRest() {
        var query=EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(vector(1))).maxResults(2).minScore(0.8)
                .filter(KnowledgeContract.query(vector(1),REV,2).filter()).build();
        assertEquals(1,store.search(query).matches().size());
        var bad=hit("chunk-0",0.8f,REV);when(bad.getValuesList()).thenReturn(List.of());response(List.of(bad));
        assertThrows(RuntimeException.class,()->KnowledgeContract.fromAdapter(store.search(query),manifest,REV));
    }
    @Test void defaultTextKeyLosesLegacyTextAndFactoryFixesIt() {
        var wrong=PineconeEmbeddingStore.builder().apiKey("offline-placeholder").index(INDEX).nameSpace(NS).build();
        var result=wrong.search(KnowledgeContract.query(vector(1),REV,2));
        assertNull(result.matches().get(0).embedded());
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.fromAdapter(result,manifest,REV));
        assertNotNull(store.search(KnowledgeContract.query(vector(1),REV,2)).matches().get(0).embedded());
    }
    @Test void sourceAndRevisionChecksMustRemainAboveTheAdapter() {
        response(List.of(hit("chunk-0",0.8f,"wrong-revision")));
        var result=store.search(KnowledgeContract.query(vector(1),REV,2));
        assertEquals(1,result.matches().size(),"适配器信任SDK过滤结果，不自行验证本地清单");
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.fromAdapter(result,manifest,REV));
        response(List.of(hit("chunk-0",0.8f,REV),hit("chunk-0",0.8f,REV)));
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.fromAdapter(store.search(KnowledgeContract.query(vector(1),REV,2)),manifest,REV));
    }
    @Test void numericMetadataReturnsDoubleAndInvalidVectorsAreRejected() {
        var result=store.search(KnowledgeContract.query(vector(1),REV,2));
        assertInstanceOf(Double.class,result.matches().get(0).embedded().metadata().toMap().get("index"));
        assertEquals(0,KnowledgeContract.fromAdapter(result,manifest,REV).get(0).index());
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.query(new float[3],REV,2));
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.query(new float[1024],REV,2));
        var invalid=vector(1);invalid[5]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->KnowledgeContract.query(invalid,REV,2));
        assertThrows(IllegalArgumentException.class,()->PineconeStoreFactory.create("offline",INDEX,""));
    }
    @Test @SuppressWarnings({"rawtypes","unchecked"}) void writesExplicitIdsWithCompatibleMetadata() {
        var chunk=manifest.get("chunk-0");
        store.addAll(List.of("chunk-0"),List.of(Embedding.from(vector(1))),List.of(KnowledgeContract.segment(chunk,REV)));
        ArgumentCaptor<List> capture=ArgumentCaptor.forClass(List.class);
        verify(index).upsert(capture.capture(),eq(NS));
        var value=(VectorWithUnsignedIndices)capture.getValue().get(0);
        assertEquals("chunk-0",value.getId());
        assertEquals(1024,value.getValuesList().size());
        assertEquals(chunk.text(),value.getMetadata().getFieldsOrThrow("text").getStringValue());
        assertEquals(chunk.source(),value.getMetadata().getFieldsOrThrow("source").getStringValue());
        assertEquals(REV,value.getMetadata().getFieldsOrThrow("revision").getStringValue());
        assertEquals(0,value.getMetadata().getFieldsOrThrow("index").getNumberValue());
        assertFalse(value.getMetadata().containsFields("text_segment"));
    }
}
