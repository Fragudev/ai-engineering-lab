package io.github.fragudev.ailab.ingestion;

import io.github.fragudev.ailab.shared.DocumentId;
import io.github.fragudev.ailab.shared.IngestionJobId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "ingestion_job")
public class IngestionJob {

    @Id
    private UUID id;

    @Column(name = "document_id")
    private UUID documentId;

    @Enumerated(EnumType.STRING)
    private JobStage stage;

    private int attempts;

    @Nullable
    @Column(name = "last_error", columnDefinition = "text")
    private String lastError;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected IngestionJob() {
        // JPA
    }

    public IngestionJob(IngestionJobId id, DocumentId documentId) {
        this.id = id.value();
        this.documentId = documentId.value();
        this.stage = JobStage.UPLOADED;
        this.attempts = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public IngestionJobId id() {
        return IngestionJobId.of(id);
    }

    public DocumentId documentId() {
        return DocumentId.of(documentId);
    }

    public JobStage stage() {
        return stage;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable String lastError() {
        return lastError;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void advanceTo(JobStage stage) {
        this.stage = stage;
        this.attempts = 0;
        this.lastError = null;
        this.updatedAt = Instant.now();
    }

    public void recordAttempt() {
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public void fail(String lastError) {
        this.stage = JobStage.FAILED;
        this.lastError = lastError;
        this.updatedAt = Instant.now();
    }
}
