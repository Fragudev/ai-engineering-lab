package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.ingestion.IngestionJob;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IngestionJobRepository extends JpaRepository<IngestionJob, UUID> {

    Optional<IngestionJob> findByDocumentId(UUID documentId);
}
