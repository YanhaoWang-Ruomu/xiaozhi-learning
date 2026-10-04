package com.ruomu.xiaozhi.service;

import com.ruomu.xiaozhi.config.KnowledgeEmbeddingConfig;
import com.ruomu.xiaozhi.dto.KnowledgeSearchResponse;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.inmemory.InMemoryEmbeddingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class KnowledgeSearchService {

    private static final Logger log =
            LoggerFactory.getLogger(KnowledgeSearchService.class);

    private static final int MAX_RESULTS = 3;

    // 本阶段用于观察排序，暂不按相似度过滤候选片段。
    private static final double MIN_SCORE = 0.0;

    private final KnowledgeDocumentService documentService;
    private final EmbeddingModel embeddingModel;

    private volatile Index index;

    public KnowledgeSearchService(
            KnowledgeDocumentService documentService,
            @Qualifier("knowledgeEmbeddingModel")
            EmbeddingModel embeddingModel
    ) {
        this.documentService = documentService;
        this.embeddingModel = embeddingModel;
    }

    public KnowledgeSearchResponse search(String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "query 不能为空"
            );
        }

        String normalizedQuery = query.strip();

        if (normalizedQuery.length() > 500) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "query 不能超过 500 个字符"
            );
        }

        try {
            Index current = getOrCreateIndex();

            // beta3 的 Qwen 实现根据 type 元数据区分查询和文档。
            TextSegment querySegment = TextSegment.from(
                    normalizedQuery,
                    Metadata.from("type", "query")
            );

            Embedding queryEmbedding =
                    embeddingModel.embed(querySegment).content();

            validateEmbedding(queryEmbedding, current.dimension());

            var result = current.store().search(
                    EmbeddingSearchRequest.builder()
                            .queryEmbedding(queryEmbedding)
                            .maxResults(MAX_RESULTS)
                            .minScore(MIN_SCORE)
                            .build()
            );

            List<KnowledgeSearchResponse.Match> matches =
                    result.matches().stream()
                            .map(match -> new KnowledgeSearchResponse.Match(
                                    match.embedded().metadata()
                                            .getInteger("index"),
                                    match.embedded().metadata()
                                            .getString("source"),
                                    match.score(),
                                    match.embedded().text()
                            ))
                            .toList();

            return new KnowledgeSearchResponse(
                    normalizedQuery,
                    KnowledgeEmbeddingConfig.MODEL_NAME,
                    current.dimension(),
                    current.chunkCount(),
                    MAX_RESULTS,
                    MIN_SCORE,
                    matches
            );
        } catch (RuntimeException e) {
            log.warn(
                    "知识检索失败，异常类型：{}",
                    e.getClass().getSimpleName()
            );

            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "向量化或检索失败，请检查网络、百炼模型访问权限及可用额度后重试",
                    e
            );
        }
    }

    // 首次检索时建立索引；并发请求不会重复建立成功的索引。
    private synchronized Index getOrCreateIndex() {
        if (index != null) {
            return index;
        }

        List<TextSegment> segments =
                documentService.preview().chunks().stream()
                        .map(chunk -> TextSegment.from(
                                chunk.text(),
                                Metadata.from("source", chunk.source())
                                        .put("index", chunk.index())
                                        .put("type", "document")
                        ))
                        .toList();

        if (segments.isEmpty()) {
            throw new IllegalStateException(
                    "没有可建立索引的文档片段"
            );
        }

        List<Embedding> embeddings =
                embeddingModel.embedAll(segments).content();

        if (embeddings == null
                || embeddings.size() != segments.size()) {
            throw new IllegalStateException(
                    "返回的向量数量与文档片段数量不一致"
            );
        }

        Embedding first = embeddings.get(0);
        int dimension = first == null ? 0 : first.dimension();

        for (Embedding embedding : embeddings) {
            validateEmbedding(embedding, dimension);
        }

        InMemoryEmbeddingStore<TextSegment> store =
                new InMemoryEmbeddingStore<>();

        store.addAll(embeddings, segments);

        // 全部成功后才发布索引；失败后再次请求可以重新建立。
        index = new Index(store, dimension, segments.size());

        log.info(
                "演示向量索引建立完成：chunks={}, dimension={}",
                segments.size(),
                dimension
        );

        return index;
    }

    private static void validateEmbedding(
            Embedding embedding,
            int dimension
    ) {
        if (embedding == null
                || dimension <= 0
                || embedding.dimension() != dimension) {
            throw new IllegalStateException(
                    "向量为空或维度不一致"
            );
        }

        double squaredNorm = 0;

        for (float value : embedding.vector()) {
            if (!Float.isFinite(value)) {
                throw new IllegalStateException(
                        "向量包含无效数值"
                );
            }

            squaredNorm += (double) value * value;
        }

        if (squaredNorm == 0) {
            throw new IllegalStateException(
                    "向量不能全部为零"
            );
        }
    }

    private record Index(
            InMemoryEmbeddingStore<TextSegment> store,
            int dimension,
            int chunkCount
    ) {
    }
}