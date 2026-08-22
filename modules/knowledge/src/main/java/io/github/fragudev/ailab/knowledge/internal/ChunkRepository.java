package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.Chunk;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByOrdinalAsc(UUID documentId);

    Optional<Chunk> findByDocumentIdAndOrdinal(UUID documentId, int ordinal);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);

    /**
     * Nearest neighbours by cosine distance (lower = more similar), via the {@code hibernate-vector}
     * HQL function — verified against Hibernate/Spring Data JPA reference docs rather than guessed
     * (AGENTS.md rule 1): {@code cosine_distance(...)} maps to pgvector's {@code <=>} operator.
     */
    @Query("SELECT new io.github.fragudev.ailab.knowledge.internal.VectorCandidateRow(c, "
            + "cosine_distance(c.embedding, :queryVector)) FROM Chunk c "
            + "ORDER BY cosine_distance(c.embedding, :queryVector) ASC")
    List<VectorCandidateRow> findNearestByEmbedding(@Param("queryVector") float[] queryVector, Limit limit);

    /**
     * Lexical matches by {@code ts_rank} (higher = more relevant) against {@code content_tsv}, the
     * Postgres GENERATED tsvector column from the V3 migration — native SQL because that column
     * isn't JPA-mapped (deliberately: nothing writes it, Postgres derives it).
     */
    @Query(
            value = "SELECT id, ts_rank(content_tsv, plainto_tsquery('english', :query)) AS rank "
                    + "FROM chunk WHERE content_tsv @@ plainto_tsquery('english', :query) "
                    + "ORDER BY rank DESC LIMIT :limit",
            nativeQuery = true)
    List<LexicalCandidateRow> findLexicalMatches(@Param("query") String query, @Param("limit") int limit);
}
