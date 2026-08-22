package io.github.fragudev.ailab.evaluation;

import io.github.fragudev.ailab.shared.EvalDatasetId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "eval_dataset", uniqueConstraints = @UniqueConstraint(columnNames = {"name", "version"}))
public class EvalDataset {

    @Id
    private UUID id;

    private String name;

    private String version;

    @Column(name = "created_at")
    private Instant createdAt;

    protected EvalDataset() {
        // JPA
    }

    public EvalDataset(EvalDatasetId id, String name, String version) {
        this.id = id.value();
        this.name = name;
        this.version = version;
        this.createdAt = Instant.now();
    }

    public EvalDatasetId id() {
        return EvalDatasetId.of(id);
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
