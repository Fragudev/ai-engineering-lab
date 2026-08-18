package io.github.fragudev.ailab;

import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record SearchResultResponse(
        UUID chunkId,
        UUID documentId,
        String content,
        @Nullable Double vectorDistance,
        @Nullable Double lexicalRank,
        double fusedScore,
        @Nullable Double rerankScore,
        int finalRank) {

    static SearchResultResponse from(SearchResult result) {
        return new SearchResultResponse(
                result.chunk().id(),
                result.chunk().documentId(),
                result.chunk().content(),
                result.vectorDistance(),
                result.lexicalRank(),
                result.fusedScore(),
                result.rerankScore(),
                result.finalRank());
    }
}
