package io.github.fragudev.ailab.aiprovider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** Post-roadmap review issue #37: during Phase 8's live evaluation run, {@code LlmReranker} fell
 * back to fused order on every single call against a real model, and the only signal was a log
 * line — this test pins the counter that now makes that kind of silent, total degradation visible
 * as a flat line at 100% instead. Real {@link SimpleMeterRegistry}, not a mock (matching {@code
 * StageRunnerTest}'s own convention for the equally thin {@code WorkflowMetrics}). */
class LlmDegradationMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final LlmDegradationMetrics metrics = new LlmDegradationMetrics(registry);

    @Test
    void recordsATimeoutWithTheCallersComponentLabel() {
        metrics.recordProviderFailure("query-normalizer", new ProviderTimeoutException("test", Duration.ofSeconds(5)));

        assertThat(counterValue("query-normalizer", "timeout")).isEqualTo(1.0);
    }

    @Test
    void recordsAProviderUnavailableFailure() {
        metrics.recordProviderFailure("llm-reranker", new ProviderUnavailableException("test", new RuntimeException()));

        assertThat(counterValue("llm-reranker", "provider-unavailable")).isEqualTo(1.0);
    }

    @Test
    void recordsAnyOtherRuntimeExceptionAsAGenericProviderError() {
        metrics.recordProviderFailure("llm-judge", new IllegalStateException("unexpected"));

        assertThat(counterValue("llm-judge", "provider-error")).isEqualTo(1.0);
    }

    @Test
    void recordsAParseFailureSeparatelyFromAProviderFailure() {
        metrics.recordParseFailure("sub-query-planner");

        assertThat(counterValue("sub-query-planner", "parse-failure")).isEqualTo(1.0);
    }

    @Test
    void eachComponentAndReasonCombinationHasItsOwnCount() {
        metrics.recordProviderFailure("llm-reranker", new ProviderTimeoutException("test", Duration.ofSeconds(5)));
        metrics.recordProviderFailure("llm-reranker", new ProviderTimeoutException("test", Duration.ofSeconds(5)));
        metrics.recordParseFailure("llm-reranker");

        assertThat(counterValue("llm-reranker", "timeout")).isEqualTo(2.0);
        assertThat(counterValue("llm-reranker", "parse-failure")).isEqualTo(1.0);
    }

    private double counterValue(String component, String reason) {
        var counter = registry.find("llm_degradation_total")
                .tag("component", component)
                .tag("reason", reason)
                .counter();
        return counter == null ? 0.0 : counter.count();
    }
}
