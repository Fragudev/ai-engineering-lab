package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Resolves a golden-dataset case's {@code "title#ordinal"} gold chunk reference (e.g.
 * {@code "pgvector#5"}) into a real chunk id — see {@link io.github.fragudev.ailab.evaluation.EvalCase}'s
 * javadoc for why the stored ref isn't a raw UUID. {@code title} is what {@code scripts/seed.sh}
 * sets a document's title to (the corpus manifest's id), and {@code ordinal} is the chunk's packing
 * order from {@code ingestion}'s deterministic {@code Chunker} — reproducible across a fresh
 * fetch+seed of the same source content.
 *
 * <p>Caches by ref for the lifetime of this singleton bean — an eval run resolves the same refs
 * repeatedly across cases/profiles/repetitions.
 */
@Component
public class GoldChunkResolver {

    private final IngestionService ingestionService;
    private final ChunkService chunkService;
    private final Map<String, Optional<UUID>> cache = new HashMap<>();

    public GoldChunkResolver(IngestionService ingestionService, ChunkService chunkService) {
        this.ingestionService = ingestionService;
        this.chunkService = chunkService;
    }

    public Optional<UUID> resolve(String ref) {
        return cache.computeIfAbsent(ref, this::resolveUncached);
    }

    private Optional<UUID> resolveUncached(String ref) {
        int separator = ref.lastIndexOf('#');
        if (separator < 0) {
            return Optional.empty();
        }
        String title = ref.substring(0, separator);
        int ordinal;
        try {
            ordinal = Integer.parseInt(ref.substring(separator + 1));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
        return ingestionService
                .findByTitle(title)
                .flatMap(document ->
                        chunkService.findByDocumentIdAndOrdinal(document.id().value(), ordinal))
                .map(Chunk::id);
    }
}
