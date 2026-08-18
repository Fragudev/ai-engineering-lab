package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.Chunk;

/** One row of a JPQL {@code cosine_distance} projection — lower {@code distance} is more similar. */
public record VectorCandidateRow(Chunk chunk, double distance) {}
