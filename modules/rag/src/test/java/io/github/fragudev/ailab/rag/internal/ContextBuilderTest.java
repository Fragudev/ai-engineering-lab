package io.github.fragudev.ailab.rag.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.SearchResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-function coverage for the greedy budget packing, always-include-one guarantee, and marker
 * numbering (post-roadmap review issue #30) — none of this needed Postgres or Kafka. */
class ContextBuilderTest {

    private final ContextBuilder builder = new ContextBuilder();

    @Test
    void packsAsManyChunksAsFitTheBudgetThenStops() {
        // budgetChars = 6 tokens * 4 = 24; two 10-char chunks fit (20 <= 24), a third pushes past it.
        SearchResult first = resultOf("0123456789", 0.9);
        SearchResult second = resultOf("9876543210", 0.8);
        SearchResult third = resultOf("abcdefghij", 0.7);

        ContextBuilder.Context context = builder.build(List.of(first, second, third), 6);

        assertThat(context.referencesByMarker()).hasSize(2);
        assertThat(context.delimitedContext()).contains("0123456789").contains("9876543210");
        assertThat(context.delimitedContext()).doesNotContain("abcdefghij");
    }

    @Test
    void alwaysIncludesAtLeastOneChunkEvenWhenItAloneExceedsTheBudget() {
        SearchResult oversized = resultOf("x".repeat(100), 0.9);

        ContextBuilder.Context context = builder.build(List.of(oversized), 1);

        assertThat(context.referencesByMarker()).hasSize(1);
        assertThat(context.delimitedContext()).contains("x".repeat(100));
    }

    @Test
    void numbersMarkersSequentiallyInInputOrder() {
        SearchResult a = resultOf("chunk-a", 0.9);
        SearchResult b = resultOf("chunk-b", 0.8);
        SearchResult c = resultOf("chunk-c", 0.7);

        ContextBuilder.Context context = builder.build(List.of(a, b, c), 1000);

        assertThat(context.referencesByMarker().keySet()).containsExactly(1, 2, 3);
        int posA = context.delimitedContext().indexOf("[1] chunk-a");
        int posB = context.delimitedContext().indexOf("[2] chunk-b");
        int posC = context.delimitedContext().indexOf("[3] chunk-c");
        assertThat(posA).isPositive();
        assertThat(posA).isLessThan(posB);
        assertThat(posB).isLessThan(posC);
    }

    @Test
    void wrapsTheBlockInTheUntrustedContentDelimiters() {
        ContextBuilder.Context context = builder.build(List.of(resultOf("content", 0.5)), 1000);

        assertThat(context.delimitedContext()).startsWith("<<<RETRIEVED_CONTEXT>>>");
        assertThat(context.delimitedContext()).endsWith("<<<END_RETRIEVED_CONTEXT>>>");
    }

    @Test
    void referenceScorePrefersRerankScoreOverFusedScore() {
        Chunk chunk = chunkWith("content");
        SearchResult withRerank = new SearchResult(chunk, 0.1, null, 0.5, 0.9, 1);

        ContextBuilder.Context context = builder.build(List.of(withRerank), 1000);

        assertThat(context.referencesByMarker().get(1).score()).isEqualTo(0.9);
    }

    @Test
    void referenceScoreFallsBackToFusedScoreWhenNoRerankScore() {
        Chunk chunk = chunkWith("content");
        SearchResult withoutRerank = new SearchResult(chunk, 0.1, null, 0.5, null, 1);

        ContextBuilder.Context context = builder.build(List.of(withoutRerank), 1000);

        assertThat(context.referencesByMarker().get(1).score()).isEqualTo(0.5);
    }

    private static SearchResult resultOf(String content, double fusedScore) {
        return new SearchResult(chunkWith(content), null, null, fusedScore, null, 1);
    }

    private static Chunk chunkWith(String content) {
        return new Chunk(UUID.randomUUID(), UUID.randomUUID(), 0, content, 0, null, new float[0]);
    }
}
