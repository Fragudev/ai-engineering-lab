package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.rag.RagAnswer;

/** Whether a turn actually declined to answer — the closest thing to a direct hallucination
 * measurement (docs/ai-evaluation.md §3), which is why the {@code unanswerable} case category
 * exists at all. Detected structurally, not by matching the canned message text: {@code RagPipeline}'s
 * abstention path is the only one that ever sets {@code model = "none"} (docs/adr/0008-rag-pipeline-architecture.md). */
public final class AbstentionMetrics {

    private AbstentionMetrics() {}

    public static boolean abstained(RagAnswer answer) {
        return "none".equals(answer.model());
    }
}
