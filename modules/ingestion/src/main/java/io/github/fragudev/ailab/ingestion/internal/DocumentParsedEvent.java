package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

@Externalized("ingestion.document.parsed.v1::#{#this.documentId()}")
public record DocumentParsedEvent(
        UUID eventId,
        String type,
        String source,
        String subject,
        Instant time,
        UUID correlationId,
        UUID causationId,
        UUID documentId,
        String text)
        implements EventEnvelope, DocumentScoped {

    public static DocumentParsedEvent of(DocumentUploadedEvent causedBy, String text) {
        return new DocumentParsedEvent(
                UUID.randomUUID(),
                "ingestion.document.parsed.v1",
                "ai-lab/ingestion",
                "document/" + causedBy.documentId(),
                Instant.now(),
                causedBy.correlationId(),
                causedBy.eventId(),
                causedBy.documentId(),
                text);
    }
}
