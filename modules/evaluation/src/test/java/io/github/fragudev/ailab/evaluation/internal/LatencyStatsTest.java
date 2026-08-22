package io.github.fragudev.ailab.evaluation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class LatencyStatsTest {

    @Test
    void p50AndP95AreComputedFromSortedSamples() {
        List<Duration> samples = List.of(
                Duration.ofMillis(100),
                Duration.ofMillis(200),
                Duration.ofMillis(300),
                Duration.ofMillis(400),
                Duration.ofMillis(500),
                Duration.ofMillis(600),
                Duration.ofMillis(700),
                Duration.ofMillis(800),
                Duration.ofMillis(900),
                Duration.ofMillis(1000));

        LatencyStats stats = LatencyStats.of(samples);

        assertThat(stats.p50()).isEqualTo(Duration.ofMillis(500));
        assertThat(stats.p95()).isEqualTo(Duration.ofMillis(1000));
    }

    @Test
    void meanIsTheAverageOfAllSamples() {
        List<Duration> samples = List.of(Duration.ofMillis(100), Duration.ofMillis(200), Duration.ofMillis(300));

        assertThat(LatencyStats.of(samples).mean()).isEqualTo(Duration.ofMillis(200));
    }

    @Test
    void singleSampleIsItsOwnP50P95AndMean() {
        LatencyStats stats = LatencyStats.of(List.of(Duration.ofMillis(42)));

        assertThat(stats.p50()).isEqualTo(Duration.ofMillis(42));
        assertThat(stats.p95()).isEqualTo(Duration.ofMillis(42));
        assertThat(stats.mean()).isEqualTo(Duration.ofMillis(42));
    }
}
