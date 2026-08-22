package io.github.fragudev.ailab;

import io.github.fragudev.ailab.workflow.WorkflowRunSnapshot;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

record WorkflowRunResponse(
        UUID id,
        String type,
        String status,
        Map<String, Object> input,
        Map<String, Object> output,
        UUID correlationId,
        List<WorkflowStepResponse> steps,
        Instant createdAt,
        Instant updatedAt) {

    static WorkflowRunResponse from(WorkflowRunSnapshot snapshot) {
        return new WorkflowRunResponse(
                snapshot.id().value(),
                snapshot.type().slug(),
                snapshot.status().name(),
                snapshot.input(),
                snapshot.output(),
                snapshot.correlationId(),
                snapshot.steps().stream().map(WorkflowStepResponse::from).toList(),
                snapshot.createdAt(),
                snapshot.updatedAt());
    }
}
