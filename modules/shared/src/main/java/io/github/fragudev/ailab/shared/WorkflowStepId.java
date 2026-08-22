package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code WorkflowStep}, so it can't be confused with any other id. */
public record WorkflowStepId(UUID value) {

    public static WorkflowStepId generate() {
        return new WorkflowStepId(UUID.randomUUID());
    }

    public static WorkflowStepId of(UUID value) {
        return new WorkflowStepId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
