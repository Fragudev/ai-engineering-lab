package io.github.fragudev.ailab.workflow.internal;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Enforces {@code ai.workflow.max-llm-calls-per-run} — the real, enforced T5 (denial of wallet)
 * mitigation docs/threat-model.md already names as planned. Scoped to one {@code
 * DocumentationResearchEngine#run} invocation, not cumulative across a restart+resume — a named,
 * accepted limitation (docs/adr/0010-agent-orchestration.md), not a precisely engineered guarantee.
 */
final class LlmCallBudget {

    private final int max;
    private final AtomicInteger used = new AtomicInteger();

    LlmCallBudget(int max) {
        this.max = max;
    }

    void consume() {
        int count = used.incrementAndGet();
        if (count > max) {
            throw new IllegalStateException("Workflow LLM call budget exceeded (max %d per run)".formatted(max));
        }
    }
}
