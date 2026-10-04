package com.ruomu.xiaozhi.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruomu.xiaozhi.config.KnowledgeEmbeddingConfig;
import com.ruomu.xiaozhi.dto.KnowledgePreviewResponse.Chunk;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class KnowledgeSearchService {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeSearchService.class);

    private static final int MAX_RESULTS = 3;

    private static final String MODEL =
            KnowledgeEmbeddingConfig.MODEL_NAME;

    private final EmbeddingModel embeddingModel;
    private final PineconeClient pinecone;

    private final Map<String, Chunk> chunks = new LinkedHashMap<>();

    private final String revision;
    private final String namespace;

    public KnowledgeSearchService(
            KnowledgeDocumentService documents,
            @Qualifier("knowledgeEmbeddingModel") EmbeddingModel embeddingModel,
            PineconeClient pinecone) {

        this.embeddingModel = embeddingModel;
        this.pinecone = pinecone;

        var preview = documents.preview();

        for (Chunk chunk : preview.chunks()) {
            if (chunks.put("chunk-" + chunk.index(), chunk) != null) {
                throw new IllegalStateException("文档片段编号重复");
            }
        }

        if (chunks.isEmpty()) {
            throw new IllegalStateException("知识文档没有可同步的片段");
        }

        try {
            // 内容、模型或切分参数变化时，使用新的命名空间，避免混入旧资料。
            byte[] manifest = new ObjectMapper().writeValueAsBytes(List.of(
                    "xiaozhi-rag-v1",
                    MODEL,
                    PineconeClient.DIMENSION,
                    preview
            ));

            revision = HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(manifest)
            );

            namespace = "demo-" + revision;

        } catch (Exception e) {
            throw new IllegalStateException("无法计算知识文档版本", e);
        }
    }

    public KnowledgeStatus knowledgeStatus() {
        int stored = existing().size();

        return new KnowledgeStatus(
                namespace,
                chunks.size(),
                stored,
                stored == chunks.size()
        );
    }

    // 只在显式调用同步接口时写入；相同内容使用相同 namespace 和 ID。
    public synchronized SyncResponse sync() {
        try {
            pinecone.stats();

            var found = existing();

            var missing = chunks.entrySet().stream()
                    .filter(entry -> !found.contains(entry.getKey()))
                    .toList();

            if (!missing.isEmpty()) {
                var segments = missing.stream()
                        .map(entry -> TextSegment.from(
                                entry.getValue().text(),
                                Metadata.from("type", "document")
                        ))
                        .toList();

                var embeddings = embeddingModel.embedAll(segments).content();

                if (embeddings == null
                        || embeddings.size() != missing.size()) {
                    throw new IllegalStateException("文档向量数量不一致");
                }

                List<Map<String, Object>> vectors = new ArrayList<>();

                for (int i = 0; i < missing.size(); i++) {
                    validate(embeddings.get(i));

                    var entry = missing.get(i);

                    vectors.add(Map.of(
                            "id", entry.getKey(),
                            "values", embeddings.get(i).vector(),
                            "metadata", metadata(entry.getValue())
                    ));
                }

                pinecone.upsert(namespace, vectors);
            }

            int stored = existing().size();
            boolean complete = stored == chunks.size();

            log.info(
                    "Pinecone 同步：uploaded={}, reused={}, stored={}, namespace={}",
                    missing.size(),
                    found.size(),
                    stored,
                    namespace
            );

            return new SyncResponse(
                    complete ? "SYNCED" : "INDEXING",
                    namespace,
                    chunks.size(),
                    missing.size(),
                    found.size(),
                    stored,
                    complete
                            ? "当前版本文档已可读取；检索索引更新可能稍有延迟"
                            : "写入已受理，请稍后刷新状态，等待当前版本全部可读取"
            );

        } catch (ResponseStatusException e) {
            throw e;

        } catch (RuntimeException e) {
            throw modelError(e);
        }
    }

    public KnowledgeSearchResponse search(String query) {
        if (query == null
                || query.isBlank()
                || query.strip().length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "query 不能为空且不能超过 500 个字符"
            );
        }

        String text = query.strip();

        try {
            if (existing().size() != chunks.size()) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "当前版本知识尚未同步完整，请先调用 Pinecone 同步接口；刚同步后请稍等"
                );
            }

            Embedding embedding = embeddingModel.embed(
                    TextSegment.from(text, Metadata.from("type", "query"))
            ).content();

            validate(embedding);

            int expected = Math.min(MAX_RESULTS, chunks.size());

            JsonNode results = pinecone.query(
                    namespace,
                    revision,
                    embedding.vector(),
                    expected
            );

            if (results.size() != expected) {
                throw new ResponseStatusException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Pinecone 检索索引可能尚未更新完整，请稍后重试"
                );
            }

            List<KnowledgeSearchResponse.Match> matches = new ArrayList<>();
            var seen = new HashSet<String>();

            for (JsonNode result : results) {
                String id = result.path("id").asText();
                Chunk chunk = chunks.get(id);

                JsonNode data = result.path("metadata");
                JsonNode score = result.path("score");

                double cosine = score.asDouble(Double.NaN);

                if (chunk == null
                        || !seen.add(id)
                        || !metadataMatches(data, chunk)
                        || !score.isNumber()
                        || !Double.isFinite(cosine)
                        || cosine < -1.00001
                        || cosine > 1.00001) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY,
                            "Pinecone 检索结果校验失败"
                    );
                }

                // 与之前 InMemoryEmbeddingStore 的分数范围保持一致。
                double normalizedScore =
                        Math.max(0, Math.min(1, (cosine + 1) / 2));

                matches.add(new KnowledgeSearchResponse.Match(
                        chunk.index(),
                        data.path("source").asText(),
                        normalizedScore,
                        data.path("text").asText()
                ));
            }

            matches.sort(
                    Comparator.comparingDouble(
                            KnowledgeSearchResponse.Match::score
                    ).reversed()
            );

            return new KnowledgeSearchResponse(
                    text,
                    MODEL,
                    PineconeClient.DIMENSION,
                    chunks.size(),
                    MAX_RESULTS,
                    0.0,
                    List.copyOf(matches)
            );

        } catch (ResponseStatusException e) {
            throw e;

        } catch (RuntimeException e) {
            throw modelError(e);
        }
    }

    private HashSet<String> existing() {
        var records = pinecone.fetch(
                namespace,
                List.copyOf(chunks.keySet())
        );

        var valid = new HashSet<String>();

        chunks.forEach((id, chunk) -> {
            JsonNode record = records.get(id);

            if (record != null
                    && id.equals(record.path("id").asText())
                    && metadataMatches(record.path("metadata"), chunk)
                    && validValues(record.path("values"))) {
                valid.add(id);
            }
        });

        return valid;
    }

    private Map<String, Object> metadata(Chunk chunk) {
        return Map.of(
                "source", chunk.source(),
                "index", chunk.index(),
                "text", chunk.text(),
                "revision", revision,
                "model", MODEL,
                "document_type", "DEMO"
        );
    }

    private boolean metadataMatches(JsonNode data, Chunk chunk) {
        JsonNode index = data.path("index");

        return data.isObject()
                && index.isNumber()
                && index.doubleValue() == chunk.index()
                && chunk.source().equals(data.path("source").asText())
                && chunk.text().equals(data.path("text").asText())
                && revision.equals(data.path("revision").asText())
                && MODEL.equals(data.path("model").asText())
                && "DEMO".equals(data.path("document_type").asText());
    }

    private static boolean validValues(JsonNode values) {
        if (!values.isArray()
                || values.size() != PineconeClient.DIMENSION) {
            return false;
        }

        double norm = 0;

        for (JsonNode value : values) {
            if (!value.isNumber()
                    || !Float.isFinite(value.floatValue())) {
                return false;
            }

            norm += value.doubleValue() * value.doubleValue();
        }

        return norm > 0 && Double.isFinite(norm);
    }

    private static void validate(Embedding embedding) {
        if (embedding == null
                || embedding.dimension() != PineconeClient.DIMENSION) {
            throw new IllegalStateException("向量维度必须为 1024");
        }

        double norm = 0;

        for (float value : embedding.vector()) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException("向量存在无效数值");
            }

            norm += (double) value * value;
        }

        if (norm == 0) {
            throw new IllegalStateException("向量不能全部为零");
        }
    }

    private static ResponseStatusException modelError(RuntimeException e) {
        log.warn(
                "知识向量操作失败，异常类型：{}",
                e.getClass().getSimpleName()
        );

        return new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "知识向量处理失败，请检查百炼模型访问权限、网络及额度后重试"
        );
    }

    public record KnowledgeStatus(
            String namespace,
            int expectedChunks,
            int storedChunks,
            boolean documentsPresent) {
    }

    public record SyncResponse(
            String status,
            String namespace,
            int expectedChunks,
            int uploadedChunks,
            int reusedChunks,
            int storedChunks,
            String message) {
    }
}