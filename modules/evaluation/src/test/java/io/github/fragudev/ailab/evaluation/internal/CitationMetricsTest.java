package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.rag.RagCitationResult;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CitationMetricsTest {

    private static RagCitationResult citation(int marker, UUID chunkId) {
        return new RagCitationResult(marker, UUID.randomUUID(), chunkId, 0.9, null);
    }

    @Test
    void precisionIsUndefinedWhenNothingWasCited() {
        assertThat(CitationMetrics.precision(List.of(), Set.of(UUID.randomUUID())))
                .isNaN();
    }

    @Test
    void precisionIsShareOfCitedChunksThatAreGold() {
        UUID gold = UUID.randomUUID();
        UUID notGold = UUID.randomUUID();
        List<RagCitationResult> citations = List.of(citation(1, gold), citation(2, notGold));

        assertThat(CitationMetrics.precision(citations, Set.of(gold))).isEqualTo(0.5);
    }

    @Test
    void precisionIsOneWhenAllCitationsAreGold() {
        UUID gold = UUID.randomUUID();
        List<RagCitationResult> citations = List.of(citation(1, gold));

        assertThat(CitationMetrics.precision(citations, Set.of(gold))).isEqualTo(1.0);
    }

    @Test
    void recallIsUndefinedWhenGoldSetIsEmpty() {
        assertThat(CitationMetrics.recall(List.of(), Set.of())).isNaN();
    }

    @Test
    void recallIsShareOfGoldChunksActuallyCited() {
        UUID gold1 = UUID.randomUUID();
        UUID gold2 = UUID.randomUUID();
        List<RagCitationResult> citations = List.of(citation(1, gold1));

        assertThat(CitationMetrics.recall(citations, Set.of(gold1, gold2))).isEqualTo(0.5);
    }

    @Test
    void recallIsZeroWhenNoGoldChunkWasCitedAtAll() {
        UUID gold = UUID.randomUUID();
        UUID notGold = UUID.randomUUID();
        List<RagCitationResult> citations = List.of(citation(1, notGold));

        assertThat(CitationMetrics.recall(citations, Set.of(gold))).isEqualTo(0.0);
    }
}
