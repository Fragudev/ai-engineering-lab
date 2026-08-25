package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.rag.RagAnswer;

/**
 * Whether {@code RagPipeline}'s <b>deterministic abstention gate</b> fired — nothing more.
 *
 * <p>Detected structurally, not by matching the canned message text: the gate is the only path that
 * ever sets {@code model = "none"} (docs/adr/0008-rag-pipeline-architecture.md). That check is exact
 * and stays.
 *
 * <p><b>What this deliberately does not measure (post-roadmap review issue #61).</b> A turn can
 * decline in two different ways, and only one of them is visible here:
 *
 * <ol>
 *   <li>the gate refuses to generate at all, because the best vector match is farther than the
 *       profile's {@code maxVectorDistance} — structural, exact, what this class reports;
 *   <li>the gate stays silent and the <b>model itself</b> declines in prose ("the retrieved
 *       documentation doesn't mention…") — a natural-language property with no structural signal
 *       behind it whatsoever.
 * </ol>
 *
 * <p>The second case is not a hypothetical. In the first full three-profile live run
 * (eval/reports/2026-08-25-…), all four {@code UNANSWERABLE} cases were answered <em>correctly</em>
 * by the model declining on its own, while this class reported {@code false} for every one of them —
 * because the gate was, correctly, silent. Those cases are topically <em>inside</em> the corpus
 * (vector distances near 0.38 against a 0.55 threshold), which is exactly the distinction
 * docs/adr/0013-rag-abstention-threshold.md draws: the gate catches "this corpus does not cover
 * the topic", never "this specific fact is not stated".
 *
 * <p>So a low gate rate on that category is the <em>expected</em> result, not a failure — and this
 * class must not be read as a hallucination measurement. The instrument that can read prose is
 * {@link LlmJudge}, scoring the answer against the refusal-shaped {@code expectedAnswer} the golden
 * dataset already provides for every {@code UNANSWERABLE} case; {@code EvalRunner} surfaces that
 * separately as refusal correctness. See docs/ai-evaluation.md §3.
 */
public final class AbstentionMetrics {

    private AbstentionMetrics() {}

    /** True when the deterministic gate declined to generate. Never true for an answer the model
     * produced, however plainly that answer refuses. */
    public static boolean gateAbstained(RagAnswer answer) {
        return "none".equals(answer.model());
    }
}
