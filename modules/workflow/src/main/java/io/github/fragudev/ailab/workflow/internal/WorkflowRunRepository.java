package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WorkflowRunRepository extends JpaRepository<WorkflowRun, UUID> {

    /** Drives resumability: on startup, every run left {@code PENDING} or {@code RUNNING} by an
     * interrupted process is re-driven from its last completed step
     * (docs/adr/0010-agent-orchestration.md). */
    List<WorkflowRun> findByStatusIn(List<WorkflowRunStatus> statuses);
}
