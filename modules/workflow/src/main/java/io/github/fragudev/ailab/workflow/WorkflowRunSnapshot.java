package io.github.fragudev.ailab.workflow;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * A read-only view of one persisted {@code WorkflowRun}, with its steps nested inline — the shape
 * {@code GET /api/v1/workflows/runs/{id}} returns. Steps are ordered by {@code stepIndex}.
 */
public record WorkflowRunSnapshot(
        WorkflowRunId id,
        WorkflowType type,
        WorkflowRunStatus status,
        Map<String, Object> input,
        @Nullable Map<String, Object> output,
        UUID correlationId,
        List<WorkflowStepSnapshot> steps,
        Instant createdAt,
        Instant updatedAt) {}
