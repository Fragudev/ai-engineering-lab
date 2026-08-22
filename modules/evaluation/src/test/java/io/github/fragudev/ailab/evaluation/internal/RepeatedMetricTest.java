package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import org.junit.jupiter.api.Test;

class RepeatedMetricTest {

    @Test
    void meanAndSpreadOverMultipleSamples() {
        RepeatedMetric metric = RepeatedMetric.of(List.of(0.8, 0.9, 1.0));

        assertThat(metric.mean()).isCloseTo(0.9, within(1e-9));
        assertThat(metric.spread()).isCloseTo(0.2, within(1e-9));
    }

    @Test
    void singleSampleHasZeroSpread() {
        RepeatedMetric metric = RepeatedMetric.of(List.of(0.75));

        assertThat(metric.mean()).isEqualTo(0.75);
        assertThat(metric.spread()).isEqualTo(0.0);
    }

    @Test
    void nanSamplesAreExcludedFromTheAverage() {
        RepeatedMetric metric = RepeatedMetric.of(List.of(1.0, Double.NaN, 0.5));

        assertThat(metric.mean()).isCloseTo(0.75, within(1e-9));
        assertThat(metric.spread()).isCloseTo(0.5, within(1e-9));
    }

    @Test
    void allNanSamplesYieldUndefinedMeanAndZeroSpread() {
        RepeatedMetric metric = RepeatedMetric.of(List.of(Double.NaN, Double.NaN));

        assertThat(metric.mean()).isNaN();
        assertThat(metric.spread()).isEqualTo(0.0);
    }

    @Test
    void emptySamplesYieldUndefinedMeanAndZeroSpread() {
        RepeatedMetric metric = RepeatedMetric.of(List.of());

        assertThat(metric.mean()).isNaN();
        assertThat(metric.spread()).isEqualTo(0.0);
    }
}
