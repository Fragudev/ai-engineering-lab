package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import io.github.fragudev.ailab.workflow.WorkflowStepStatus;
import io.github.fragudev.ailab.workflow.WorkflowType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** {@code workflow_run_total{type,status}} and {@code workflow_step_duration_seconds{stage,status}}
 * (docs/architecture.md #12), mirroring {@code tools.internal.ToolMetrics}'s exact shape. */
@Component
public class WorkflowMetrics {

    private final MeterRegistry registry;

    public WorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void recordRun(WorkflowType type, WorkflowRunStatus status) {
        registry.counter("workflow_run_total", "type", type.slug(), "status", status.name())
                .increment();
    }

    public void recordStage(String stageName, WorkflowStepStatus status, Duration duration) {
        registry.timer("workflow_step_duration_seconds", "stage", stageName, "status", status.name())
                .record(duration);
    }
}
