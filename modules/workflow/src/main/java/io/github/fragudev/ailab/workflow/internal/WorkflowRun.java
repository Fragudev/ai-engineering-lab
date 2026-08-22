package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import io.github.fragudev.ailab.workflow.WorkflowType;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

/**
 * One run of an agentic workflow (docs/architecture.md #4, #7). Mutated in place as the state
 * machine advances — {@link #markRunning()}/{@link #markSucceeded}/{@link #markFailed} — the same
 * transition-method style as {@code ingestion.IngestionJob}.
 */
@Entity
@Table(name = "workflow_run")
public class WorkflowRun {

    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    private WorkflowType type;

    @Enumerated(EnumType.STRING)
    private WorkflowRunStatus status;

    @Convert(converter = WorkflowJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> input;

    @Convert(converter = WorkflowJsonConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Map<String, Object> output;

    @Column(name = "correlation_id")
    private UUID correlationId;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    protected WorkflowRun() {
        // JPA
    }

    public WorkflowRun(WorkflowRunId id, WorkflowType type, Map<String, Object> input, UUID correlationId) {
        this.id = id.value();
        this.type = type;
        this.status = WorkflowRunStatus.PENDING;
        this.input = input;
        this.output = null;
        this.correlationId = correlationId;
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    public WorkflowRunId id() {
        return WorkflowRunId.of(id);
    }

    public WorkflowType type() {
        return type;
    }

    public WorkflowRunStatus status() {
        return status;
    }

    public Map<String, Object> input() {
        return input;
    }

    public @Nullable Map<String, Object> output() {
        return output;
    }

    public UUID correlationId() {
        return correlationId;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void markRunning() {
        this.status = WorkflowRunStatus.RUNNING;
        this.updatedAt = Instant.now();
    }

    public void markSucceeded(Map<String, Object> output) {
        this.status = WorkflowRunStatus.SUCCEEDED;
        this.output = output;
        this.updatedAt = Instant.now();
    }

    public void markFailed(Map<String, Object> output) {
        this.status = WorkflowRunStatus.FAILED;
        this.output = output;
        this.updatedAt = Instant.now();
    }
}
