package io.github.fragudev.ailab.workflow;

/**
 * Mirrors {@code workflow_run.status} in the data model (docs/architecture.md #4, #7). No separate
 * {@code COMPENSATING}/{@code COMPENSATED} value: every stage in this workflow is read-only or
 * computational, so compensating a failed run is the transition into {@code FAILED} itself, not a
 * distinct observable phase (docs/adr/0010-agent-orchestration.md).
 */
public enum WorkflowRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED
}
