package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.evaluation.internal.LatencyStats;
import io.github.fragudev.ailab.evaluation.internal.RepeatedMetric;
import java.util.List;

/**
 * One RAG profile's aggregated results across {@link EvalRunConfig#repetitions()} full passes over
 * the dataset — the comparison table's row.
 *
 * <p>Declining is reported as two separate figures rather than one (post-roadmap review issue #61),
 * because the two mechanisms behind it are measured by different instruments and collapsing them
 * loses the signal that tells them apart:
 *
 * @param gateAbstentionRate fraction of {@code UNANSWERABLE} cases where the <b>deterministic</b>
 *     gate declined to generate. Structural and exact. A low value is the expected result whenever
 *     those cases are topically inside the corpus — see
 *     {@link io.github.fragudev.ailab.evaluation.internal.AbstentionMetrics}. Not a hallucination
 *     measurement.
 * @param refusalCorrectness the judge's correctness score restricted to {@code UNANSWERABLE} cases,
 *     where the dataset's {@code expectedAnswer} is itself a refusal — so this scores "did the turn
 *     decline correctly", by whichever mechanism, including the model declining in its own prose.
 *     {@code NaN} when the judge was not run ({@code --judge}), which is <em>not measured</em>
 *     rather than zero. Inherits every weakness docs/ai-evaluation.md §3 states about judge scores.
 */
public record ProfileSummary(
        String ragProfile,
        RepeatedMetric recallAtK,
        RepeatedMetric mrr,
        RepeatedMetric citationPrecision,
        RepeatedMetric citationRecall,
        RepeatedMetric gateAbstentionRate,
        RepeatedMetric refusalCorrectness,
        LatencyStats latency,
        long totalPromptTokens,
        long totalCompletionTokens,
        List<List<CaseResult>> repetitions) {}
