package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.rag.RagAnswer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstentionMetricsTest {

    private static RagAnswer answer(String model, String content) {
        return new RagAnswer(
                content, List.of(), List.of(), model, new TokenUsage(10, 5), Duration.ofMillis(1), BigDecimal.ZERO);
    }

    @Test
    void gateAbstainedIsTrueWhenModelIsNone() {
        assertThat(AbstentionMetrics.gateAbstained(answer("none", "some content")))
                .isTrue();
    }

    @Test
    void gateAbstainedIsFalseForAnyRealModel() {
        assertThat(AbstentionMetrics.gateAbstained(answer("recorded-fixture", "some content")))
                .isFalse();
    }

    /**
     * The case that made post-roadmap review issue #61 necessary. This exact answer came from a real
     * run (eval/reports/2026-08-25-…) against {@code qwen/qwen3.8-27b}: the model declined, correctly,
     * on an {@code UNANSWERABLE} case — and the gate had correctly stayed silent, because that case is
     * topically inside the corpus.
     *
     * <p>Reporting {@code false} here is right; this class measures the gate. What was wrong was
     * calling the resulting figure "abstention accuracy" and reading 0.00 as a hallucination. The
     * assertion is pinned so that nobody "fixes" this into substring-matching refusal phrasing —
     * which would make the metric silently text-dependent and is what its structural check exists to
     * avoid.
     */
    @Test
    void gateAbstainedIsFalseWhenTheModelItselfDeclinedInProse() {
        RagAnswer modelDeclined = answer(
                "qwen/qwen3.8-27b",
                "The retrieved documentation doesn't mention a maximum message size for the message browser.");

        assertThat(AbstentionMetrics.gateAbstained(modelDeclined)).isFalse();
    }

    @Test
    void gateAbstainedIsFalseForAnOrdinaryAnswer() {
        RagAnswer answered = answer("qwen/qwen3.8-27b", "pgvector uses the <=> operator for cosine distance.");

        assertThat(AbstentionMetrics.gateAbstained(answered)).isFalse();
    }
}
