package io.github.fragudev.ailab;

import io.github.fragudev.ailab.conversation.Citation;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** A persisted citation, as returned by {@code GET .../messages}. */
record CitationResponse(
        UUID id,
        UUID messageId,
        UUID chunkId,
        UUID documentId,
        double score,
        @Nullable String quotedSpan,
        int ordinal,
        Instant createdAt) {

    static CitationResponse from(Citation citation) {
        return new CitationResponse(
                citation.id().value(),
                citation.messageId().value(),
                citation.chunkId(),
                citation.documentId().value(),
                citation.score(),
                citation.quotedSpan(),
                citation.ordinal(),
                citation.createdAt());
    }
}
