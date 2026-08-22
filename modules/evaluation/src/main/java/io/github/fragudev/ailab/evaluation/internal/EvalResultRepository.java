package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.evaluation.EvalResult;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalResultRepository extends JpaRepository<EvalResult, UUID> {

    List<EvalResult> findByRunId(UUID runId);
}
