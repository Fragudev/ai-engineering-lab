package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.ProviderException;
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
 *
 * <p><b>Retry policy (post-roadmap review B1, docs/adr/0010-agent-orchestration.md).</b> Only a
 * {@link ProviderException} — a model-server timeout, rate limit or connection failure, the actual
 * failure modes this harness faces — is retried; any other exception (a programming error, a schema
 * violation, a malformed-response parse failure) fails the stage on its first attempt instead of
 * burning the full retry budget on something retrying cannot fix, mirroring
 * {@code NonRetryableIngestionException}'s equivalent distinction in the ingestion pipeline. Retries
 * back off exponentially ({@code ai.workflow.retry-base-delay}, doubling each attempt) instead of
 * firing immediately — three instant retries against the same rate limit or timeout accomplish
 * nothing, and Phase 8's own live run demonstrated exactly that with {@code LlmReranker} against a
 * 27B model.
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
                if (!isRetryable(e)) {
                    break;
                }
                if (attempt < totalAttempts) {
                    backOff(attempt);
                }
            }
        }

        step.markFailed(Map.of("error", String.valueOf(lastError.getMessage())));
        stepRepository.save(step);
        metrics.recordStage(name, WorkflowStepStatus.FAILED, Duration.between(start, Instant.now()));
        throw new StageFailedException(name, lastError);
    }

    private static boolean isRetryable(Exception e) {
        return e instanceof ProviderException;
    }

    /** {@code attempt} is 1-indexed and always < {@code totalAttempts} here, so this only ever
     * runs between two attempts, never after the last one. Plain {@link Thread#sleep}, not a
     * scheduler — {@code WorkflowsConfiguration}'s virtual-thread executor is exactly what makes a
     * blocking sleep here cheap rather than something worth routing around. */
    private void backOff(int attempt) {
        Duration delay = properties.retryBaseDelay().multipliedBy(1L << (attempt - 1));
        try {
            Thread.sleep(delay);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
