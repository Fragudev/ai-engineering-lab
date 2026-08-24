package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.LlmDegradationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SourceExtractorTest {

    private final LlmDegradationMetrics degradationMetrics = new LlmDegradationMetrics(new SimpleMeterRegistry());

    @Test
    void returnsExtractedFacts() {
        SourceExtractor extractor =
                new SourceExtractor(new FakeChatProvider("Water boils at 100C."), degradationMetrics);

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isEqualTo("Water boils at 100C.");
    }

    @Test
    void noneResponseDropsTheSource() {
        SourceExtractor extractor = new SourceExtractor(new FakeChatProvider("NONE"), degradationMetrics);

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
    }

    @Test
    void blankResponseDropsTheSource() {
        SourceExtractor extractor = new SourceExtractor(new FakeChatProvider("   "), degradationMetrics);

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
    }

    @Test
    void providerFailureDropsTheSourceRatherThanThrowing() {
        SourceExtractor extractor =
                new SourceExtractor(FakeChatProvider.failingWith(new RuntimeException("boom")), degradationMetrics);

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
