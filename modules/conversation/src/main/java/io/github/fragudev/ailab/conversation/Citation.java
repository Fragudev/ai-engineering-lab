package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.shared.CitationId;
import io.github.fragudev.ailab.shared.DocumentId;
import io.github.fragudev.ailab.shared.MessageId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * One resolved {@code [marker]} citation from an assistant message, denormalized at generation time
 * by {@code rag} rather than joined against {@code knowledge} at read time — this module doesn't
 * depend on {@code knowledge} (docs/architecture.md #3), so the chunk's own score and quoted span are
 * copied in as they were when the answer was generated.
 */
@Entity
@Table(name = "citation")
public class Citation {

    @Id
    private UUID id;

    @Column(name = "message_id")
    private UUID messageId;

    @Column(name = "chunk_id")
    private UUID chunkId;

    @Column(name = "document_id")
    private UUID documentId;

    private double score;

    @Nullable
    @Column(name = "quoted_span", columnDefinition = "text")
    private String quotedSpan;

    private int ordinal;

    @Column(name = "created_at")
    private Instant createdAt;

    protected Citation() {
        // JPA
    }

    public Citation(
            CitationId id,
            MessageId messageId,
            UUID chunkId,
            DocumentId documentId,
            double score,
            @Nullable String quotedSpan,
            int ordinal) {
        this.id = id.value();
        this.messageId = messageId.value();
        this.chunkId = chunkId;
        this.documentId = documentId.value();
        this.score = score;
        this.quotedSpan = quotedSpan;
        this.ordinal = ordinal;
        this.createdAt = Instant.now();
    }

    public CitationId id() {
        return CitationId.of(id);
    }

    public MessageId messageId() {
        return MessageId.of(messageId);
    }

    public UUID chunkId() {
        return chunkId;
    }

    public DocumentId documentId() {
        return DocumentId.of(documentId);
    }

    public double score() {
        return score;
    }

    public @Nullable String quotedSpan() {
        return quotedSpan;
    }

    public int ordinal() {
        return ordinal;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
