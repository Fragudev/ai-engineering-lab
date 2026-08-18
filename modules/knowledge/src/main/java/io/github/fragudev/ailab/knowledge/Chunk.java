package io.github.fragudev.ailab.knowledge;

import io.github.fragudev.ailab.knowledge.internal.JsonMetadataConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.Array;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * A chunk's {@code embedding} is fixed at 1024 dimensions project-wide (bge-m3); see
 * docs/adr/0003-persistence-and-vector-store.md. {@code content_tsv} (full-text search, Phase 3) is
 * a Postgres GENERATED column and deliberately not mapped here — nothing in this module writes it.
 */
@Entity
@Table(name = "chunk")
public class Chunk {

    @Id
    private UUID id;

    @Column(name = "document_id")
    private UUID documentId;

    private int ordinal;

    @Column(columnDefinition = "text")
    private String content;

    @Column(name = "token_count")
    private int tokenCount;

    @Nullable
    @Convert(converter = JsonMetadataConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @JdbcTypeCode(SqlTypes.VECTOR)
    @Array(length = 1024)
    private float[] embedding;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Chunk() {
        // JPA
    }

    public Chunk(
            UUID id,
            UUID documentId,
            int ordinal,
            String content,
            int tokenCount,
            @Nullable Map<String, Object> metadata,
            float[] embedding) {
        this.id = id;
        this.documentId = documentId;
        this.ordinal = ordinal;
        this.content = content;
        this.tokenCount = tokenCount;
        this.metadata = metadata;
        this.embedding = embedding;
        this.createdAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID documentId() {
        return documentId;
    }

    public int ordinal() {
        return ordinal;
    }

    public String content() {
        return content;
    }

    public int tokenCount() {
        return tokenCount;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
