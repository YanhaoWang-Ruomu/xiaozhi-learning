package com.ruomu.examples.pinecone;

import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import java.util.*;
import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/** 从主项目提炼的返回值契约；这里不实现真实同步、网络重试或权限接口。 */
public final class KnowledgeContract {
    public static final int DIMENSION = 1024;
    public static final String MODEL = "fixture-embedding";
    public record Chunk(int index, String source, String text) {}
    public record Hit(String id, int index, String source, String text, double score) {}
    private KnowledgeContract() {}

    public static EmbeddingSearchRequest query(float[] vector, String revision, int topK) {
        validateVector(vector);
        if (revision == null || revision.isBlank()) throw new IllegalArgumentException("revision不能为空");
        return EmbeddingSearchRequest.builder().queryEmbedding(Embedding.from(vector)).maxResults(topK)
                // 主项目先验证完整topK，再由聊天层应用0.80阈值；这里也不提前过滤。
                .minScore(0.0).filter(metadataKey("revision").isEqualTo(revision)).build();
    }
    public static TextSegment segment(Chunk chunk, String revision) {
        return TextSegment.from(chunk.text(), Metadata.from(Map.of(
                "source",chunk.source(),"index",chunk.index(),"revision",revision,
                "model",MODEL,"document_type","DEMO")));
    }
    public static List<Hit> fromRest(JsonNode matches, Map<String,Chunk> manifest, String revision) {
        if (!matches.isArray()) throw new IllegalArgumentException("matches必须为数组");
        List<Hit> result = new ArrayList<>();
        for (JsonNode row : matches) {
            JsonNode meta = row.path("metadata"), raw = row.path("score");
            if (!raw.isNumber() || !Double.isFinite(raw.doubleValue()) || raw.doubleValue() < -1.00001 || raw.doubleValue() > 1.00001)
                throw new IllegalArgumentException("无效cosine分数");
            double normalized = Math.max(0,Math.min(1,(raw.doubleValue()+1)/2));
            if (!meta.path("index").isNumber()) throw new IllegalArgumentException("片段编号必须为数字");
            result.add(checked(row.path("id").asText(), meta.path("index").doubleValue(),
                    meta.path("source").asText(),meta.path("text").asText(),meta.path("revision").asText(),
                    meta.path("model").asText(),meta.path("document_type").asText(),normalized,manifest,revision));
        }
        return sortedUnique(result);
    }
    public static List<Hit> fromAdapter(EmbeddingSearchResult<TextSegment> response, Map<String,Chunk> manifest, String revision) {
        List<Hit> result = new ArrayList<>();
        for (var match : response.matches()) {
            TextSegment segment = match.embedded();
            if (segment == null) throw new IllegalArgumentException("正文丢失：检查metadataTextKey");
            Map<String,Object> meta = segment.metadata().toMap();
            if (!(meta.get("index") instanceof Number number)) throw new IllegalArgumentException("片段编号必须为数字");
            result.add(checked(match.embeddingId(),number.doubleValue(),(String)meta.get("source"),segment.text(),
                    (String)meta.get("revision"),(String)meta.get("model"),(String)meta.get("document_type"),
                    match.score(),manifest,revision));
        }
        return sortedUnique(result);
    }
    private static Hit checked(String id, double index, String source, String text, String actualRevision,
            String model, String type, double score, Map<String,Chunk> manifest, String revision) {
        Chunk expected = manifest.get(id);
        if (expected == null || !Double.isFinite(index) || index != expected.index()
                || !Objects.equals(source,expected.source()) || !Objects.equals(text,expected.text())
                || !Objects.equals(actualRevision,revision) || !MODEL.equals(model) || !"DEMO".equals(type)
                || !Double.isFinite(score) || score < 0 || score > 1)
            throw new IllegalArgumentException("检索来源、版本、片段编号或分数不符合本地清单");
        return new Hit(id,expected.index(),source,text,score);
    }
    private static List<Hit> sortedUnique(List<Hit> result) {
        if (result.stream().map(Hit::id).distinct().count() != result.size()) throw new IllegalArgumentException("重复片段ID");
        return result.stream().sorted(Comparator.comparingDouble(Hit::score).reversed()).toList();
    }
    public static void validateVector(float[] vector) {
        if (vector == null || vector.length != DIMENSION) throw new IllegalArgumentException("向量必须为1024维");
        double norm = 0;
        for (float value : vector) { if (!Float.isFinite(value)) throw new IllegalArgumentException("向量必须有限"); norm += (double)value*value; }
        if (norm == 0) throw new IllegalArgumentException("不能使用零向量");
    }
}
