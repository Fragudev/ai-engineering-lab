package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.Chunk;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<Chunk, UUID> {

    List<Chunk> findByDocumentIdOrderByOrdinalAsc(UUID documentId);

    long countByDocumentId(UUID documentId);

    void deleteByDocumentId(UUID documentId);
}
