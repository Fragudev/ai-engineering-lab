package io.github.fragudev.ailab.knowledge;

import io.github.fragudev.ailab.knowledge.internal.ChunkRepository;
import java.util.List;
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

    public long countByDocumentId(UUID documentId) {
        return chunkRepository.countByDocumentId(documentId);
    }

    public void deleteByDocumentId(UUID documentId) {
        chunkRepository.deleteByDocumentId(documentId);
    }
}
