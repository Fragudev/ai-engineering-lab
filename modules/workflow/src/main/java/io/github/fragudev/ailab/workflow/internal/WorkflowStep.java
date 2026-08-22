package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.shared.WorkflowStepId;
import io.github.fragudev.ailab.workflow.WorkflowStepStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One stage of one {@link WorkflowRun} — {@code plan-sub-queries}, {@code retrieve}, {@code
 * extract-per-source}, {@code synthesise}, {@code self-check} or {@code answer}
 * (docs/architecture.md #4, #7). A fan-out stage's individual sub-task results live inside this
 * row's {@code output}, not as separate rows — stage-level, not sub-task-level, persistence
 * granularity (docs/adr/0010-agent-orchestration.md).
 */
@Entity
@Table(name = "workflow_step")
public class WorkflowStep {

    @Id
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "step_index")
    private int stepIndex;

    private String name;

    @Enumerated(EnumType.STRING)
    private WorkflowStepStatus status;

    @Convert(converter = WorkflowJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Map<String, Object> input;

    @Convert(converter = WorkflowJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Map<String, Object> output;

    private int attempts;

    @Column(name = "cost_usd")
    private @Nullable BigDecimal costUsd;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected WorkflowStep() {
        // JPA
    }

    public WorkflowStep(WorkflowStepId id, WorkflowRunId runId, int stepIndex, String name) {
        this.id = id.value();
        this.runId = runId.value();
        this.stepIndex = stepIndex;
        this.name = name;
        this.status = WorkflowStepStatus.PENDING;
        this.attempts = 0;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public WorkflowStepId id() {
        return WorkflowStepId.of(id);
    }

    public WorkflowRunId runId() {
        return WorkflowRunId.of(runId);
    }

    public int stepIndex() {
        return stepIndex;
    }

    public String name() {
        return name;
    }

    public WorkflowStepStatus status() {
        return status;
    }

    public @Nullable Map<String, Object> input() {
        return input;
    }

    public @Nullable Map<String, Object> output() {
        return output;
    }

    public int attempts() {
        return attempts;
    }

    public @Nullable BigDecimal costUsd() {
        return costUsd;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void markRunning(@Nullable Map<String, Object> input) {
        this.status = WorkflowStepStatus.RUNNING;
        this.input = input;
        this.attempts++;
        this.updatedAt = Instant.now();
    }

    public void markSucceeded(@Nullable Map<String, Object> output, @Nullable BigDecimal costUsd) {
        this.status = WorkflowStepStatus.SUCCEEDED;
        this.output = output;
        this.costUsd = costUsd;
        this.updatedAt = Instant.now();
    }

    public void markFailed(@Nullable Map<String, Object> output) {
        this.status = WorkflowStepStatus.FAILED;
        this.output = output;
        this.updatedAt = Instant.now();
    }
}
