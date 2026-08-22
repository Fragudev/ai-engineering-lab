package io.github.fragudev.ailab;

import io.github.fragudev.ailab.workflow.WorkflowStepSnapshot;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

record WorkflowStepResponse(
        String id,
        int stepIndex,
        String name,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        int attempts,
        BigDecimal costUsd,
        Instant createdAt,
        Instant updatedAt) {

    static WorkflowStepResponse from(WorkflowStepSnapshot snapshot) {
        return new WorkflowStepResponse(
                snapshot.id().toString(),
                snapshot.stepIndex(),
                snapshot.name(),
                snapshot.status().name(),
                snapshot.input(),
                snapshot.output(),
                snapshot.attempts(),
                snapshot.costUsd(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }
}
