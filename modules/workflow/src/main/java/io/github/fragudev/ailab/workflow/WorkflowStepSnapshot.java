package io.github.fragudev.ailab.workflow;

import io.github.fragudev.ailab.shared.WorkflowStepId;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * A read-only view of one persisted {@code WorkflowStep} — the public shape returned by
 * {@link WorkflowService#findRun(io.github.fragudev.ailab.shared.WorkflowRunId)}, keeping the JPA
 * entity itself in {@code internal} (same split as {@code tools.ToolInvocation}'s own entity vs. its
 * public-facing DTOs). {@code stepIndex} is the stage's fixed position in the pipeline; {@code name}
 * is one of {@code plan-sub-queries}, {@code retrieve}, {@code extract-per-source}, {@code
 * synthesise}, {@code self-check}, {@code answer}.
 */
public record WorkflowStepSnapshot(
        WorkflowStepId id,
        int stepIndex,
        String name,
        WorkflowStepStatus status,
        @Nullable Map<String, Object> input,
        @Nullable Map<String, Object> output,
        int attempts,
        @Nullable BigDecimal costUsd,
        Instant createdAt,
        Instant updatedAt) {}
