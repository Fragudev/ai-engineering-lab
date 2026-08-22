package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.shared.EvalCaseId;
import io.github.fragudev.ailab.shared.EvalDatasetId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * One golden-dataset case, loaded from {@code eval/dataset/*.yaml} by
 * {@code evaluation.internal.DatasetLoader}. {@code goldChunkRefs} are stable
 * {@code "title#ordinal"} strings, not raw chunk UUIDs — corpus content isn't committed, so a
 * committed dataset can't hardcode ids from one particular ingestion run; see
 * {@code evaluation.internal.GoldChunkResolver}.
 */
@Entity
@Table(name = "eval_case", uniqueConstraints = @UniqueConstraint(columnNames = {"dataset_id", "case_key"}))
public class EvalCase {

    @Id
    private UUID id;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "case_key")
    private String caseKey;

    @Column(columnDefinition = "text")
    private String question;

    @Column(name = "expected_answer", columnDefinition = "text")
    private String expectedAnswer;

    @Column(name = "gold_chunk_refs", columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] goldChunkRefs;

    @Column(columnDefinition = "text[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] tags;

    @Enumerated(EnumType.STRING)
    private EvalCaseCategory category;

    @Column(name = "created_at")
    private Instant createdAt;

    protected EvalCase() {
        // JPA
    }

    public EvalCase(
            EvalCaseId id,
            EvalDatasetId datasetId,
            String caseKey,
            String question,
            String expectedAnswer,
            String[] goldChunkRefs,
            String[] tags,
            EvalCaseCategory category) {
        this.id = id.value();
        this.datasetId = datasetId.value();
        this.caseKey = caseKey;
        this.question = question;
        this.expectedAnswer = expectedAnswer;
        this.goldChunkRefs = goldChunkRefs;
        this.tags = tags;
        this.category = category;
        this.createdAt = Instant.now();
    }

    public EvalCaseId id() {
        return EvalCaseId.of(id);
    }

    public EvalDatasetId datasetId() {
        return EvalDatasetId.of(datasetId);
    }

    public String caseKey() {
        return caseKey;
    }

    public String question() {
        return question;
    }

    public String expectedAnswer() {
        return expectedAnswer;
    }

    public String[] goldChunkRefs() {
        return goldChunkRefs;
    }

    public String[] tags() {
        return tags;
    }

    public EvalCaseCategory category() {
        return category;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
