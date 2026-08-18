package io.github.fragudev.ailab.knowledge.internal;

import io.github.fragudev.ailab.knowledge.Chunk;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code score = Σ 1/(k + rank)} over whichever retrievers found a chunk, {@code k=60} — the standard
 * default from the original RRF paper. A chunk found by both retrievers accumulates both terms and
 * naturally outranks one found by only one, without any tuned fusion weight.
 */
public final class ReciprocalRankFusion {

    private static final int K = 60;

    private ReciprocalRankFusion() {}

    /**
     * Both input lists are already in rank order (best first, 0-indexed). Returns fused candidates
     * sorted by {@code fusedScore} descending.
     */
    public static List<Fused> fuse(
            List<Chunk> vectorRankedChunks,
            Map<UUID, Double> vectorDistanceById,
            List<Chunk> lexicalRankedChunks,
            Map<UUID, Double> lexicalRankById) {
        Map<UUID, Chunk> chunksById = new LinkedHashMap<>();
        Map<UUID, Double> fusedScoreById = new LinkedHashMap<>();
        accumulate(vectorRankedChunks, chunksById, fusedScoreById);
        accumulate(lexicalRankedChunks, chunksById, fusedScoreById);

        return chunksById.values().stream()
                .map(chunk -> new Fused(
                        chunk,
                        vectorDistanceById.get(chunk.id()),
                        lexicalRankById.get(chunk.id()),
                        fusedScoreById.get(chunk.id())))
                .sorted(Comparator.comparingDouble(Fused::fusedScore).reversed())
                .toList();
    }

    private static void accumulate(
            List<Chunk> rankedChunks, Map<UUID, Chunk> chunksById, Map<UUID, Double> fusedScoreById) {
        for (int rank = 0; rank < rankedChunks.size(); rank++) {
            Chunk chunk = rankedChunks.get(rank);
            chunksById.putIfAbsent(chunk.id(), chunk);
            fusedScoreById.merge(chunk.id(), 1.0 / (K + rank + 1), Double::sum);
        }
    }

    public record Fused(
            Chunk chunk,
            @Nullable Double vectorDistance,
            @Nullable Double lexicalRank,
            double fusedScore) {}
}
