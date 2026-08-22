package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.shared.EvalDatasetId;
import io.github.fragudev.ailab.shared.EvalRunId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "eval_run")
public class EvalRun {

    @Id
    private UUID id;

    @Column(name = "dataset_id")
    private UUID datasetId;

    @Column(name = "rag_profile")
    private String ragProfile;

    private String model;

    @Nullable
    private String hardware;

    @Column(name = "started_at")
    private Instant startedAt;

    @Nullable
    @Column(name = "finished_at")
    private Instant finishedAt;

    protected EvalRun() {
        // JPA
    }

    public EvalRun(EvalRunId id, EvalDatasetId datasetId, String ragProfile, String model, @Nullable String hardware) {
        this.id = id.value();
        this.datasetId = datasetId.value();
        this.ragProfile = ragProfile;
        this.model = model;
        this.hardware = hardware;
        this.startedAt = Instant.now();
    }

    public void markFinished() {
        this.finishedAt = Instant.now();
    }

    public EvalRunId id() {
        return EvalRunId.of(id);
    }

    public EvalDatasetId datasetId() {
        return EvalDatasetId.of(datasetId);
    }

    public String ragProfile() {
        return ragProfile;
    }

    public String model() {
        return model;
    }

    public @Nullable String hardware() {
        return hardware;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public @Nullable Instant finishedAt() {
        return finishedAt;
    }
}
