package io.github.fragudev.ailab.rag.internal;

import java.util.UUID;

/** What {@link ContextBuilder} knows about a numbered context entry, before the answer exists —
 * {@code score} is the chunk's fused-or-reranked ranking score, kept for the eventual citation. */
public record ChunkReference(int marker, UUID documentId, UUID chunkId, double score) {}
