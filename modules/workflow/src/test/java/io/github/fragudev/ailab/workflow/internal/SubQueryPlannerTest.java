package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.LlmDegradationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SubQueryPlannerTest {

    private final LlmDegradationMetrics degradationMetrics = new LlmDegradationMetrics(new SimpleMeterRegistry());

    @Test
    void splitsResponseIntoOneSubQueryPerLine() {
        SubQueryPlanner planner =
                new SubQueryPlanner(new FakeChatProvider("Question one?\nQuestion two?"), degradationMetrics);

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("Question one?", "Question two?");
    }

    @Test
    void capsAtMaxSubQueries() {
        SubQueryPlanner planner = new SubQueryPlanner(new FakeChatProvider("Q1\nQ2\nQ3\nQ4\nQ5"), degradationMetrics);

        SubQueryPlanner.PlanResult result = planner.plan("original query", 2);

        assertThat(result.subQueries()).containsExactly("Q1", "Q2");
    }

    @Test
    void fallsBackToOriginalQueryWhenResponseIsBlank() {
        SubQueryPlanner planner = new SubQueryPlanner(new FakeChatProvider("   "), degradationMetrics);

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("original query");
    }

    @Test
    void fallsBackToOriginalQueryWhenProviderFails() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        LlmDegradationMetrics metrics = new LlmDegradationMetrics(registry);
        SubQueryPlanner planner =
                new SubQueryPlanner(FakeChatProvider.failingWith(new RuntimeException("boom")), metrics);

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("original query");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        // Post-roadmap review issue #37: a forced provider failure must be visible as a real,
        // non-zero counter, not just a log line — the exact gap that let LlmReranker fall back on
        // every single call during Phase 8's live run without anyone noticing until reading logs.
        assertThat(registry.find("llm_degradation_total")
                        .tag("component", "sub-query-planner")
                        .tag("reason", "provider-error")
                        .counter()
                        .count())
                .isEqualTo(1.0);
    }
}
