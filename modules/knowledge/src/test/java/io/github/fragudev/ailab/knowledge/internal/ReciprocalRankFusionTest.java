package io.github.fragudev.ailab.knowledge.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.knowledge.Chunk;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-function coverage for {@code score = Σ 1/(k + rank)} fusion and ordering
 * (post-roadmap review issue #30) — none of this needed Postgres or Kafka. */
class ReciprocalRankFusionTest {

    private static final int K = 60;

    @Test
    void aChunkFoundByBothRetrieversOutranksOneFoundByOnlyOne() {
        Chunk foundByBoth = chunk();
        Chunk foundByVectorOnly = chunk();

        List<ReciprocalRankFusion.Fused> fused = ReciprocalRankFusion.fuse(
                List.of(foundByBoth, foundByVectorOnly), Map.of(), List.of(foundByBoth), Map.of());

        assertThat(fused.get(0).chunk()).isEqualTo(foundByBoth);
        assertThat(fused.get(1).chunk()).isEqualTo(foundByVectorOnly);
    }

    @Test
    void scoreIsTheSumOfReciprocalRanksAcrossBothRetrievers() {
        Chunk onlyChunk = chunk();

        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(List.of(onlyChunk), Map.of(), List.of(onlyChunk), Map.of());

        double expected = 1.0 / (K + 1) + 1.0 / (K + 1);
        assertThat(fused.get(0).fusedScore()).isEqualTo(expected);
    }

    @Test
    void aBetterRankScoresHigherThanAWorseRankFromTheSameRetriever() {
        Chunk rankedFirst = chunk();
        Chunk rankedSecond = chunk();

        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(List.of(rankedFirst, rankedSecond), Map.of(), List.of(), Map.of());

        assertThat(fused.get(0).chunk()).isEqualTo(rankedFirst);
        assertThat(fused.get(0).fusedScore()).isGreaterThan(fused.get(1).fusedScore());
    }

    @Test
    void resultsAreSortedByFusedScoreDescending() {
        Chunk a = chunk();
        Chunk b = chunk();
        Chunk c = chunk();
        // b: found by both retrievers at rank 0 (highest score). a: vector-only rank 0. c: lexical-only rank 1.
        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(List.of(b, a), Map.of(), List.of(b, c), Map.of());

        assertThat(fused).extracting(ReciprocalRankFusion.Fused::chunk).containsExactly(b, a, c);
        assertThat(fused).isSortedAccordingTo((x, y) -> Double.compare(y.fusedScore(), x.fusedScore()));
    }

    @Test
    void carriesVectorDistanceAndLexicalRankThroughUnchanged() {
        Chunk onlyChunk = chunk();
        Map<UUID, Double> vectorDistanceById = Map.of(onlyChunk.id(), 0.42);
        Map<UUID, Double> lexicalRankById = Map.of(onlyChunk.id(), 3.0);

        List<ReciprocalRankFusion.Fused> fused =
                ReciprocalRankFusion.fuse(List.of(onlyChunk), vectorDistanceById, List.of(onlyChunk), lexicalRankById);

        assertThat(fused.get(0).vectorDistance()).isEqualTo(0.42);
        assertThat(fused.get(0).lexicalRank()).isEqualTo(3.0);
    }

    @Test
    void emptyInputsProduceNoResults() {
        assertThat(ReciprocalRankFusion.fuse(List.of(), Map.of(), List.of(), Map.of()))
                .isEmpty();
    }

    private static Chunk chunk() {
        return new Chunk(UUID.randomUUID(), UUID.randomUUID(), 0, "content", 0, null, new float[0]);
    }
}
