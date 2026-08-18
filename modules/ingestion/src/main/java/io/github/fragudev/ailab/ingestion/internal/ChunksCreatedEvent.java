package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.EventEnvelope;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.modulith.events.Externalized;

@Externalized("ingestion.chunks.created.v1::#{#this.documentId()}")
public record ChunksCreatedEvent(
        UUID eventId,
        String type,
        String source,
        String subject,
        Instant time,
        UUID correlationId,
        UUID causationId,
        UUID documentId,
        List<ChunkDraft> chunks)
        implements EventEnvelope, DocumentScoped {

    static ChunksCreatedEvent of(DocumentParsedEvent causedBy, List<ChunkDraft> chunks) {
        return new ChunksCreatedEvent(
                UUID.randomUUID(),
                "ingestion.chunks.created.v1",
                "ai-lab/ingestion",
                "document/" + causedBy.documentId(),
                Instant.now(),
                causedBy.correlationId(),
                causedBy.eventId(),
                causedBy.documentId(),
                chunks);
    }
}
