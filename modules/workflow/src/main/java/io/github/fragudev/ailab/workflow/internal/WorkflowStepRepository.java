package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, UUID> {

    List<WorkflowStep> findByRunIdOrderByStepIndexAsc(UUID runId);

    default List<WorkflowStep> findByRun(WorkflowRunId runId) {
        return findByRunIdOrderByStepIndexAsc(runId.value());
    }
}
