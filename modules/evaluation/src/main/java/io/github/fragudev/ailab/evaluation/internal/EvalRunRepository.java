package io.github.fragudev.ailab.evaluation.internal;

import io.github.fragudev.ailab.evaluation.EvalRun;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EvalRunRepository extends JpaRepository<EvalRun, UUID> {}
