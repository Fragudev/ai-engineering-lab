package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RetrievalMetricsTest {

    private static SearchResult resultAt(UUID chunkId, int rank) {
        Chunk chunk = new Chunk(chunkId, UUID.randomUUID(), rank, "content", 1, null, new float[] {0f});
        return new SearchResult(chunk, 0.1, null, 1.0 / rank, null, rank);
    }

    @Test
    void recallAtKIsUndefinedWhenGoldSetIsEmpty() {
        assertThat(RetrievalMetrics.recallAtK(List.of(), Set.of())).isNaN();
    }

    @Test
    void recallAtKIsShareOfGoldChunksFound() {
        UUID gold1 = UUID.randomUUID();
        UUID gold2 = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<SearchResult> results = List.of(resultAt(gold1, 1), resultAt(other, 2));

        assertThat(RetrievalMetrics.recallAtK(results, Set.of(gold1, gold2))).isEqualTo(0.5);
    }

    @Test
    void recallAtKIsOneWhenAllGoldChunksFound() {
        UUID gold = UUID.randomUUID();
        List<SearchResult> results = List.of(resultAt(gold, 1));

        assertThat(RetrievalMetrics.recallAtK(results, Set.of(gold))).isEqualTo(1.0);
    }

    @Test
    void reciprocalRankIsUndefinedWhenGoldSetIsEmpty() {
        assertThat(RetrievalMetrics.reciprocalRank(List.of(), Set.of())).isNaN();
    }

    @Test
    void reciprocalRankIsInverseOfFirstGoldChunkRank() {
        UUID gold = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<SearchResult> results = List.of(resultAt(other, 1), resultAt(gold, 2));

        assertThat(RetrievalMetrics.reciprocalRank(results, Set.of(gold))).isEqualTo(0.5);
    }

    @Test
    void reciprocalRankIsZeroWhenNoGoldChunkRetrieved() {
        UUID gold = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<SearchResult> results = List.of(resultAt(other, 1));

        assertThat(RetrievalMetrics.reciprocalRank(results, Set.of(gold))).isEqualTo(0.0);
    }

    @Test
    void reciprocalRankUsesTheBestRankWhenGoldChunkAppearsMultipleTimes() {
        UUID gold = UUID.randomUUID();
        List<SearchResult> results = List.of(resultAt(gold, 3), resultAt(gold, 1));

        assertThat(RetrievalMetrics.reciprocalRank(results, Set.of(gold))).isEqualTo(1.0);
    }
}
