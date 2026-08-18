package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.EventEnvelope;
import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

@Externalized("ingestion.document.indexed.v1::#{#this.documentId()}")
public record DocumentIndexedEvent(
        UUID eventId,
        String type,
        String source,
        String subject,
        Instant time,
        UUID correlationId,
        UUID causationId,
        UUID documentId,
        int chunkCount)
        implements EventEnvelope {

    static DocumentIndexedEvent of(ChunksCreatedEvent causedBy, int chunkCount) {
        return new DocumentIndexedEvent(
                UUID.randomUUID(),
                "ingestion.document.indexed.v1",
                "ai-lab/ingestion",
                "document/" + causedBy.documentId(),
                Instant.now(),
                causedBy.correlationId(),
                causedBy.eventId(),
                causedBy.documentId(),
                chunkCount);
    }
}
