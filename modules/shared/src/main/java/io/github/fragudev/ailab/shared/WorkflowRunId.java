package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code WorkflowRun}, so it can't be confused with any other id. */
public record WorkflowRunId(UUID value) {

    public static WorkflowRunId generate() {
        return new WorkflowRunId(UUID.randomUUID());
    }

    public static WorkflowRunId of(UUID value) {
        return new WorkflowRunId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
