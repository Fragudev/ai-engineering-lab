package io.github.fragudev.ailab.rag;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One resolved citation — a {@code [marker]} the model actually used, mapped back to its source
 * chunk. {@code quotedSpan} is best-effort: the sentence of the generated answer that carried the
 * marker, not a quote from the source chunk itself.
 */
public record RagCitationResult(
        int marker,
        UUID documentId,
        UUID chunkId,
        double score,
        @Nullable String quotedSpan) {}
