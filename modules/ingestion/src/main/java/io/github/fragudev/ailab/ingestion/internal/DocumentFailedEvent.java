package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

/** Published by whichever stage's retries are exhausted (docs/adr/0005-kafka.md). */
@Externalized("ingestion.document.failed.v1::#{#this.documentId()}")
public record DocumentFailedEvent(
        UUID eventId,
        String type,
        String source,
        String subject,
        Instant time,
        UUID correlationId,
        UUID causationId,
        UUID documentId,
        String stage,
        String errorMessage)
        implements EventEnvelope {

    static DocumentFailedEvent of(
            UUID correlationId, UUID causationId, UUID documentId, String stage, String errorMessage) {
        return new DocumentFailedEvent(
                UUID.randomUUID(),
                "ingestion.document.failed.v1",
                "ai-lab/ingestion",
                "document/" + documentId,
                Instant.now(),
                correlationId,
                causationId,
                documentId,
                stage,
                errorMessage);
    }
}
