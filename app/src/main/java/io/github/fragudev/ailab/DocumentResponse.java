package io.github.fragudev.ailab;

import io.github.fragudev.ailab.ingestion.Document;
import io.github.fragudev.ailab.ingestion.DocumentStatus;
import java.time.Instant;
import java.util.UUID;

record DocumentResponse(
        UUID id,
        String sourceUri,
        String title,
        String mimeType,
        String contentHash,
        DocumentStatus status,
        Instant createdAt) {

    static DocumentResponse from(Document document) {
        return new DocumentResponse(
                document.id().value(),
                document.sourceUri(),
                document.title(),
                document.mimeType(),
                document.contentHash(),
                document.status(),
                document.createdAt());
    }
}
