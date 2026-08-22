package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.rag.RagAnswer;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class AbstentionMetricsTest {

    private static RagAnswer answerWithModel(String model) {
        return new RagAnswer(
                "some content",
                List.of(),
                List.of(),
                model,
                new TokenUsage(10, 5),
                Duration.ofMillis(1),
                BigDecimal.ZERO);
    }

    @Test
    void abstainedIsTrueWhenModelIsNone() {
        assertThat(AbstentionMetrics.abstained(answerWithModel("none"))).isTrue();
    }

    @Test
    void abstainedIsFalseForAnyRealModel() {
        assertThat(AbstentionMetrics.abstained(answerWithModel("recorded-fixture")))
                .isFalse();
    }
}
