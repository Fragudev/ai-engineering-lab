package io.github.fragudev.ailab.evaluation;

/** The five case categories docs/ai-evaluation.md scopes the golden dataset around, chosen so
 * failure modes are distinguishable rather than averaged away. */
public enum EvalCaseCategory {
    FACTUAL_SINGLE_HOP,
    MULTI_HOP,
    EXACT_TERM,
    UNANSWERABLE,
    AMBIGUOUS
}
