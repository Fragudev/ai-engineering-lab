package io.github.fragudev.ailab;

import io.github.fragudev.ailab.ingestion.IngestionJob;
import io.github.fragudev.ailab.ingestion.JobStage;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record IngestionJobResponse(
        UUID id,
        UUID documentId,
        JobStage stage,
        int attempts,
        @Nullable String lastError,
        Instant updatedAt) {

    static IngestionJobResponse from(IngestionJob job) {
        return new IngestionJobResponse(
                job.id().value(),
                job.documentId().value(),
                job.stage(),
                job.attempts(),
                job.lastError(),
                job.updatedAt());
    }
}
