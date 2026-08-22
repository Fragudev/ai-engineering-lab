package io.github.fragudev.ailab.evaluation.internal;

import java.time.Duration;
import java.util.List;

/** p50/p95/mean over a set of real per-case latency samples (docs/ai-evaluation.md §3). Under the
 * {@code recorded} profile these measure harness/mechanism overhead, not real model latency — the
 * report says so explicitly rather than implying otherwise. */
public record LatencyStats(Duration p50, Duration p95, Duration mean) {

    public static LatencyStats of(List<Duration> samples) {
        if (samples.isEmpty()) {
            return new LatencyStats(Duration.ZERO, Duration.ZERO, Duration.ZERO);
        }
        List<Duration> sorted = samples.stream().sorted().toList();
        long meanMillis =
                (long) sorted.stream().mapToLong(Duration::toMillis).average().orElse(0);
        return new LatencyStats(percentile(sorted, 0.50), percentile(sorted, 0.95), Duration.ofMillis(meanMillis));
    }

    private static Duration percentile(List<Duration> sorted, double p) {
        int index = (int) Math.ceil(p * sorted.size()) - 1;
        index = Math.max(0, Math.min(index, sorted.size() - 1));
        return sorted.get(index);
    }
}
