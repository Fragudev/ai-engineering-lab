package io.github.fragudev.ailab.evaluation;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * One case's real, measured metrics from one run — see docs/ai-evaluation.md §3 for definitions.
 * {@code Double.NaN} means undefined for this case (e.g. citation precision with zero citations),
 * not zero. {@code gateAbstained} is {@code null} unless the case is tagged
 * {@link EvalCaseCategory#UNANSWERABLE}. {@code judgeCorrectness}/{@code judgeFaithfulness} are
 * {@code null} unless {@link EvalRunConfig#runJudge()} was set.
 *
 * <p>{@code gateAbstained} reports only whether the <b>deterministic</b> gate fired, which is the
 * only part of declining that is structurally observable — a model that declines in its own prose
 * records {@code false} here (post-roadmap review issue #61). For an {@code UNANSWERABLE} case that
 * is the expected reading, not a failure: see
 * {@link io.github.fragudev.ailab.evaluation.internal.AbstentionMetrics} for why, and
 * {@code judgeCorrectness} — scored against the refusal-shaped {@code expectedAnswer} the dataset
 * provides for those cases — for whether the turn actually declined correctly.
 */
public record CaseMetrics(
        double recallAtK,
        double reciprocalRank,
        double citationPrecision,
        double citationRecall,
        @Nullable Boolean gateAbstained,
        Duration latency,
        int promptTokens,
        int completionTokens,
        @Nullable Double judgeCorrectness,
        @Nullable Double judgeFaithfulness) {

    /** For {@code EvalResult.metrics} (JSONB) — {@code NaN}/{@code null} fields are simply omitted
     * rather than serialized as an invalid JSON number. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        putIfFinite(map, "recallAtK", recallAtK);
        putIfFinite(map, "reciprocalRank", reciprocalRank);
        putIfFinite(map, "citationPrecision", citationPrecision);
        putIfFinite(map, "citationRecall", citationRecall);
        if (gateAbstained != null) {
            map.put("gateAbstained", gateAbstained);
        }
        map.put("latencyMs", latency.toMillis());
        map.put("promptTokens", promptTokens);
        map.put("completionTokens", completionTokens);
        if (judgeCorrectness != null) {
            map.put("judgeCorrectness", judgeCorrectness);
        }
        if (judgeFaithfulness != null) {
            map.put("judgeFaithfulness", judgeFaithfulness);
        }
        return map;
    }

    private static void putIfFinite(Map<String, Object> map, String key, double value) {
        if (!Double.isNaN(value)) {
            map.put(key, value);
        }
    }
}
