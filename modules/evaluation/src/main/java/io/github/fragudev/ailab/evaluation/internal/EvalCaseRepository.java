package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.evaluation.EvalCase;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalCaseRepository extends JpaRepository<EvalCase, UUID> {

    List<EvalCase> findByDatasetId(UUID datasetId);

    Optional<EvalCase> findByDatasetIdAndCaseKey(UUID datasetId, String caseKey);
}
