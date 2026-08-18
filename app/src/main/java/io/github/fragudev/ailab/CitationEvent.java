package io.github.fragudev.ailab;

import io.github.fragudev.ailab.rag.RagCitationResult;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The payload of the SSE `citation` event — fires before the citation is persisted, so unlike
 * {@link CitationResponse} it has no id, no messageId, no createdAt yet. */
record CitationEvent(
        UUID chunkId,
        UUID documentId,
        double score,
        @Nullable String quotedSpan,
        int marker) {

    static CitationEvent from(RagCitationResult citation) {
        return new CitationEvent(
                citation.chunkId(), citation.documentId(), citation.score(), citation.quotedSpan(), citation.marker());
    }
}
