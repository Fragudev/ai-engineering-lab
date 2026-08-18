package io.github.fragudev.ailab.knowledge.internal;

import java.util.UUID;

/**
 * A native-query interface projection: {@code content_tsv} (Postgres GENERATED tsvector, V3
 * migration) isn't a JPA-mapped field, so this comes from raw SQL rather than JPQL — see
 * {@link ChunkRepository#findLexicalMatches}.
 */
public interface LexicalCandidateRow {

    UUID getId();

    double getRank();
}
