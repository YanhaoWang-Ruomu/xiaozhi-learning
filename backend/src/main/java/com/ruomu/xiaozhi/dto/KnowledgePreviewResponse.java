package com.ruomu.xiaozhi.dto;

import java.util.List;

public record KnowledgePreviewResponse(
        String source,
        int chunkCount,
        int maxSegmentSizeInChars,
        int maxOverlapSizeInChars,
        List<Chunk> chunks
) {
    public record Chunk(
            int index,
            String source,
            int characterCount,
            String text
    ) {
    }
}