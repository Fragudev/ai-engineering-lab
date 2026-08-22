package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class SourceExtractorTest {

    @Test
    void returnsExtractedFacts() {
        SourceExtractor extractor = new SourceExtractor(new FakeChatProvider("Water boils at 100C."));

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isEqualTo("Water boils at 100C.");
    }

    @Test
    void noneResponseDropsTheSource() {
        SourceExtractor extractor = new SourceExtractor(new FakeChatProvider("NONE"));

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
    }

    @Test
    void blankResponseDropsTheSource() {
        SourceExtractor extractor = new SourceExtractor(new FakeChatProvider("   "));

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
    }

    @Test
    void providerFailureDropsTheSourceRatherThanThrowing() {
        SourceExtractor extractor = new SourceExtractor(FakeChatProvider.failingWith(new RuntimeException("boom")));

        SourceExtractor.ExtractResult result = extractor.extract("query", "passage");

        assertThat(result.facts()).isNull();
        assertThat(result.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
