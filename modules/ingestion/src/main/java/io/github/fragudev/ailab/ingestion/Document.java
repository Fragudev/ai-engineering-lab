package io.github.fragudev.ailab.ingestion;

import io.github.fragudev.ailab.ingestion.internal.JsonMetadataConverter;
import io.github.fragudev.ailab.shared.DocumentId;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * {@code contentHash} is load-bearing: it gives upload deduplication for free (docs/architecture.md
 * #4) — uploading the same bytes twice returns the existing document instead of creating another.
 */
@Entity
@Table(name = "document")
public class Document {

    @Id
    private UUID id;

    @Column(name = "source_uri")
    private String sourceUri;

    private String title;

    @Column(name = "mime_type")
    private String mimeType;

    @Column(name = "content_hash")
    private String contentHash;

    @Enumerated(EnumType.STRING)
    private DocumentStatus status;

    @Nullable
    @Convert(converter = JsonMetadataConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Document() {
        // JPA
    }

    public Document(DocumentId id, String sourceUri, String title, String mimeType, String contentHash) {
        this.id = id.value();
        this.sourceUri = sourceUri;
        this.title = title;
        this.mimeType = mimeType;
        this.contentHash = contentHash;
        this.status = DocumentStatus.PENDING;
        this.createdAt = Instant.now();
    }

    public DocumentId id() {
        return DocumentId.of(id);
    }

    public String sourceUri() {
        return sourceUri;
    }

    public String title() {
        return title;
    }

    public String mimeType() {
        return mimeType;
    }

    public String contentHash() {
        return contentHash;
    }

    public DocumentStatus status() {
        return status;
    }

    public void markIndexed() {
        this.status = DocumentStatus.INDEXED;
    }

    public void markFailed() {
        this.status = DocumentStatus.FAILED;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
