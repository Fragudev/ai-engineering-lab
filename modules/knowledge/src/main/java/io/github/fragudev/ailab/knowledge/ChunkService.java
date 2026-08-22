package io.github.fragudev.ailab.knowledge;

import io.github.fragudev.ailab.knowledge.internal.ChunkRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class ChunkService {

    private final ChunkRepository chunkRepository;

    public ChunkService(ChunkRepository chunkRepository) {
        this.chunkRepository = chunkRepository;
    }

    public List<Chunk> saveAll(List<Chunk> chunks) {
        return chunkRepository.saveAll(chunks);
    }

    public List<Chunk> findByDocumentId(UUID documentId) {
        return chunkRepository.findByDocumentIdOrderByOrdinalAsc(documentId);
    }

    /** Used to resolve a golden-dataset case's "title#ordinal" gold chunk reference against real
     * ingested content — see {@code evaluation.internal.GoldChunkResolver}. */
    public Optional<Chunk> findByDocumentIdAndOrdinal(UUID documentId, int ordinal) {
        return chunkRepository.findByDocumentIdAndOrdinal(documentId, ordinal);
    }

    public long countByDocumentId(UUID documentId) {
        return chunkRepository.countByDocumentId(documentId);
    }

    public void deleteByDocumentId(UUID documentId) {
        chunkRepository.deleteByDocumentId(documentId);
    }
}
