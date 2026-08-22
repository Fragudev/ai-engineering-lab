package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SubQueryPlannerTest {

    @Test
    void splitsResponseIntoOneSubQueryPerLine() {
        SubQueryPlanner planner = new SubQueryPlanner(new FakeChatProvider("Question one?\nQuestion two?"));

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("Question one?", "Question two?");
    }

    @Test
    void capsAtMaxSubQueries() {
        SubQueryPlanner planner = new SubQueryPlanner(new FakeChatProvider("Q1\nQ2\nQ3\nQ4\nQ5"));

        SubQueryPlanner.PlanResult result = planner.plan("original query", 2);

        assertThat(result.subQueries()).containsExactly("Q1", "Q2");
    }

    @Test
    void fallsBackToOriginalQueryWhenResponseIsBlank() {
        SubQueryPlanner planner = new SubQueryPlanner(new FakeChatProvider("   "));

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("original query");
    }

    @Test
    void fallsBackToOriginalQueryWhenProviderFails() {
        SubQueryPlanner planner = new SubQueryPlanner(FakeChatProvider.failingWith(new RuntimeException("boom")));

        SubQueryPlanner.PlanResult result = planner.plan("original query", 4);

        assertThat(result.subQueries()).containsExactly("original query");
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
