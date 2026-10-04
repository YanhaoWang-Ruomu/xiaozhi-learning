package com.ruomu.xiaozhi.dto;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        String modelName,
        int embeddingDimension,
        int indexedChunkCount,
        int maxResults,
        double minScore,
        List<Match> matches
) {
    public record Match(
            int index,
            String source,
            double score,
            String text
    ) {
    }
}