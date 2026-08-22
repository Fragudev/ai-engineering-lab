package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.evaluation.EvalDataset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalDatasetRepository extends JpaRepository<EvalDataset, UUID> {

    Optional<EvalDataset> findByNameAndVersion(String name, String version);
}
