package io.github.fragudev.ailab.ingestion.internal;

/** A chunk before it has an embedding — produced by the chunker, consumed by the embedder. */
record ChunkDraft(int ordinal, String content) {}
