package com.ruomu.xiaozhi.dto;

import java.util.List;

public record KnowledgePreviewResponse(
        String source,
        int chunkCount,
        int maxSegmentSizeInChars,
        int maxOverlapSizeInChars,
        List<Chunk> chunks,
        int documentCount,
        List<DocumentSummary> documents
) {
    // index 是整个当前知识库内的片段编号，从 0 开始，不能在每份文件中重新计数。
    // 保留原有字段，现有检索、Pinecone 元数据和页面来源展示可以继续使用。
    public record Chunk(int index, String source, int characterCount, String text) {
    }

    public record DocumentSummary(
            String source,
            int characterCount,
            int firstChunkIndex,
            int chunkCount
    ) {
    }

    // 兼容已有的五参数调用；多文档加载器使用上面的完整构造方法。
    public KnowledgePreviewResponse(String source, int chunkCount,
            int maxSegmentSizeInChars, int maxOverlapSizeInChars, List<Chunk> chunks) {
        this(source, chunkCount, maxSegmentSizeInChars, maxOverlapSizeInChars, chunks,
                (int) chunks.stream().map(Chunk::source).distinct().count(), List.of());
    }
}
