package io.github.fragudev.ailab.workflow;

/** Mirrors {@code workflow_step.status} in the data model (docs/architecture.md #4, #7). */
public enum WorkflowStepStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
