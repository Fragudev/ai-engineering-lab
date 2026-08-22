package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.evaluation.internal.MetricsConverter;
import io.github.fragudev.ailab.shared.EvalCaseId;
import io.github.fragudev.ailab.shared.EvalResultId;
import io.github.fragudev.ailab.shared.EvalRunId;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "eval_result")
public class EvalResult {

    @Id
    private UUID id;

    @Column(name = "run_id")
    private UUID runId;

    @Column(name = "case_id")
    private UUID caseId;

    @Column(columnDefinition = "text")
    private String answer;

    @Convert(converter = MetricsConverter.class)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> metrics;

    @Column(name = "created_at")
    private Instant createdAt;

    protected EvalResult() {
        // JPA
    }

    public EvalResult(EvalResultId id, EvalRunId runId, EvalCaseId caseId, String answer, Map<String, Object> metrics) {
        this.id = id.value();
        this.runId = runId.value();
        this.caseId = caseId.value();
        this.answer = answer;
        this.metrics = metrics;
        this.createdAt = Instant.now();
    }

    public EvalResultId id() {
        return EvalResultId.of(id);
    }

    public EvalRunId runId() {
        return EvalRunId.of(runId);
    }

    public EvalCaseId caseId() {
        return EvalCaseId.of(caseId);
    }

    public String answer() {
        return answer;
    }

    public Map<String, Object> metrics() {
        return metrics;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
