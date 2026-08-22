package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.shared.WorkflowStepId;
import io.github.fragudev.ailab.workflow.WorkflowStepStatus;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The shared persist/retry/compensate harness every one of {@link DocumentationResearchEngine}'s
 * six stages runs through — justified since six near-identical stage-wrapping blocks is well past
 * the threshold for extracting a helper. Persists the step {@code RUNNING} before executing, retries
 * transient failures up to {@code ai.workflow.stage-retry-attempts} additional times, and on final
 * exhaustion marks the step and (via the thrown {@link StageFailedException}) the run {@code FAILED}
 * — the real, explicit "compensation" action docs/roadmap.md's Phase 6 acceptance criteria require,
 * not merely an uncaught exception (docs/adr/0010-agent-orchestration.md).
 */
@Component
class StageRunner {

    private static final Logger log = LoggerFactory.getLogger(StageRunner.class);

    private final WorkflowStepRepository stepRepository;
    private final WorkflowsProperties properties;
    private final WorkflowMetrics metrics;

    StageRunner(WorkflowStepRepository stepRepository, WorkflowsProperties properties, WorkflowMetrics metrics) {
        this.stepRepository = stepRepository;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * Runs one stage for {@code runId}, reusing {@code existing} (a step row left {@code RUNNING} by
     * an interrupted process) instead of creating a new one when present — how a resumed run
     * continues an in-flight stage's attempt count truthfully rather than resetting it.
     */
    Map<String, Object> run(
            WorkflowRunId runId,
            int stepIndex,
            String name,
            @Nullable Map<String, Object> input,
            @Nullable WorkflowStep existing,
            StageFunction function) {
        WorkflowStep step =
                existing != null ? existing : new WorkflowStep(WorkflowStepId.generate(), runId, stepIndex, name);
        Instant start = Instant.now();
        int totalAttempts = 1 + properties.stageRetryAttempts();
        Exception lastError = null;

        for (int attempt = 1; attempt <= totalAttempts; attempt++) {
            step.markRunning(input);
            stepRepository.save(step);
            try {
                StageOutcome outcome = function.execute();
                step.markSucceeded(outcome.output(), outcome.costUsd());
                stepRepository.save(step);
                metrics.recordStage(name, WorkflowStepStatus.SUCCEEDED, Duration.between(start, Instant.now()));
                return outcome.output();
            } catch (Exception e) {
                lastError = e;
                log.warn("Stage '{}' attempt {}/{} failed for run {}", name, attempt, totalAttempts, runId, e);
            }
        }

        step.markFailed(Map.of("error", String.valueOf(lastError.getMessage())));
        stepRepository.save(step);
        metrics.recordStage(name, WorkflowStepStatus.FAILED, Duration.between(start, Instant.now()));
        throw new StageFailedException(name, lastError);
    }
}
