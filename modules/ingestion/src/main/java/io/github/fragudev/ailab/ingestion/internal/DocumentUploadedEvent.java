package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

@Externalized("ingestion.document.uploaded.v1::#{#this.documentId()}")
public record DocumentUploadedEvent(
        UUID eventId,
        String type,
        String source,
        String subject,
        Instant time,
        UUID correlationId,
        UUID causationId,
        UUID documentId,
        String title,
        String mimeType,
        String contentHash,
        // The original bytes, Base64-encoded, travel with the event rather than through a separate
        // object store — deferred, since this phase's parsing scope is plain text/markdown only
        // (see docs/adr/0006-chunking-strategy.md and the roadmap's Phase 2 scope notes).
        String contentBase64)
        implements EventEnvelope, DocumentScoped {

    public static DocumentUploadedEvent of(
            UUID correlationId,
            UUID documentId,
            String title,
            String mimeType,
            String contentHash,
            String contentBase64) {
        return new DocumentUploadedEvent(
                UUID.randomUUID(),
                "ingestion.document.uploaded.v1",
                "ai-lab/ingestion",
                "document/" + documentId,
                Instant.now(),
                correlationId,
                correlationId,
                documentId,
                title,
                mimeType,
                contentHash,
                contentBase64);
    }
}
