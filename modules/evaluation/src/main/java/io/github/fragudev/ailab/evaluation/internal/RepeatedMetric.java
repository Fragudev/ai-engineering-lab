package io.github.fragudev.ailab.evaluation.internal;

import java.util.List;

/** Mean and spread (max − min) of a deterministic metric across the {@code N}-run repetition
 * docs/ai-evaluation.md §5 asks for — local models aren't fully deterministic even at temperature
 * zero, so a single-run figure with no variance shown is an incomplete measurement. Under the
 * {@code recorded} profile, {@code spread} is legitimately {@code 0.0} (true replay determinism),
 * not faked. {@link Double#NaN} samples (an undefined per-case metric) are excluded, not averaged in. */
public record RepeatedMetric(double mean, double spread) {

    public static RepeatedMetric of(List<Double> samples) {
        List<Double> finite = samples.stream().filter(d -> !d.isNaN()).toList();
        if (finite.isEmpty()) {
            return new RepeatedMetric(Double.NaN, 0.0);
        }
        double mean = finite.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
        double max = finite.stream().mapToDouble(Double::doubleValue).max().orElse(mean);
        double min = finite.stream().mapToDouble(Double::doubleValue).min().orElse(mean);
        return new RepeatedMetric(mean, max - min);
    }
}
