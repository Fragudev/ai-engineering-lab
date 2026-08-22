package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.evaluation.internal.LatencyStats;
import io.github.fragudev.ailab.evaluation.internal.RepeatedMetric;
import java.util.List;

/** One RAG profile's aggregated results across {@link EvalRunConfig#repetitions()} full passes over
 * the dataset — the comparison table's row. */
public record ProfileSummary(
        String ragProfile,
        RepeatedMetric recallAtK,
        RepeatedMetric mrr,
        RepeatedMetric citationPrecision,
        RepeatedMetric citationRecall,
        RepeatedMetric abstentionAccuracy,
        LatencyStats latency,
        long totalPromptTokens,
        long totalCompletionTokens,
        List<List<CaseResult>> repetitions) {}
