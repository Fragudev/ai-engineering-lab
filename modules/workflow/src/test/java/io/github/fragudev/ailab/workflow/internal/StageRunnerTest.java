package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.shared.WorkflowStepId;
import io.github.fragudev.ailab.workflow.WorkflowStepStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Post-roadmap review B1 (issue #25): {@link StageRunner} used to retry every {@code
 * catch (Exception e)} with no delay between attempts. These tests pin the fix directly —
 * {@link FakeWorkflowStepRepository} stands in for the JPA repository (no mocking framework in
 * this codebase, see its own javadoc), and {@link WorkflowMetrics} is constructed against a real
 * {@link SimpleMeterRegistry} rather than faked, since it's a thin, side-effect-only wrapper.
 */
class StageRunnerTest {

    private static final Duration BASE_DELAY = Duration.ofMillis(50);

    private final FakeWorkflowStepRepository stepRepository = new FakeWorkflowStepRepository();
    private final WorkflowMetrics metrics = new WorkflowMetrics(new SimpleMeterRegistry());
    private final WorkflowsProperties properties =
            new WorkflowsProperties(true, 1, 1, 1, 2, Duration.ofSeconds(5), BASE_DELAY);
    private final StageRunner runner = new StageRunner(stepRepository, properties, metrics);

    @Test
    void succeedsOnFirstAttemptWithNoRetry() {
        Map<String, Object> result = runner.run(
                WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> StageOutcome.of(Map.of("ok", true)));

        assertThat(result).containsEntry("ok", true);
        WorkflowStep persisted = onlyStep();
        assertThat(persisted.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(persisted.attempts()).isEqualTo(1);
    }

    /** Post-roadmap review issue #31: resuming a run reuses the step row an interrupted process left
     * behind ({@code existing}, non-null) instead of creating a fresh one — {@code attempts} must
     * continue from where the prior process left off, not reset to zero. */
    @Test
    void resumingWithAnExistingStepRowContinuesItsAttemptCountRatherThanResetting() {
        WorkflowStep interrupted = new WorkflowStep(WorkflowStepId.generate(), WorkflowRunId.generate(), 0, "retrieve");
        interrupted.markRunning(Map.of()); // simulates the attempt the interrupted process was mid-way through
        assertThat(interrupted.attempts()).isEqualTo(1);

        Map<String, Object> result = runner.run(
                interrupted.runId(), 0, "retrieve", Map.of(), interrupted, () -> StageOutcome.of(Map.of("ok", true)));

        assertThat(result).containsEntry("ok", true);
        assertThat(interrupted.attempts()).isEqualTo(2);
        assertThat(stepRepository.findAll()).hasSize(1);
    }

    @Test
    void exhaustingAllRetriesLeavesTheStepFailedWithTheLastErrorPersisted() {
        assertThatThrownBy(() -> runner.run(WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> {
                    throw new ProviderTimeoutException("lmstudio", Duration.ofSeconds(30));
                }))
                .isInstanceOf(StageFailedException.class);

        WorkflowStep persisted = onlyStep();
        assertThat(persisted.status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(persisted.attempts()).isEqualTo(3); // stageRetryAttempts=2 in `properties` -> 3 total attempts
        assertThat(persisted.output()).containsEntry("error", "Provider 'lmstudio' did not respond within PT30S");
    }

    @Test
    void transientFailureSucceedsOnRetry() {
        AtomicInteger calls = new AtomicInteger();
        Map<String, Object> result = runner.run(WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> {
            if (calls.incrementAndGet() == 1) {
                throw new ProviderTimeoutException("lmstudio", Duration.ofSeconds(30));
            }
            return StageOutcome.of(Map.of("ok", true));
        });

        assertThat(calls).hasValue(2);
        assertThat(result).containsEntry("ok", true);
        WorkflowStep persisted = onlyStep();
        assertThat(persisted.status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
        assertThat(persisted.attempts()).isEqualTo(2);
    }

    @Test
    void backsOffExponentiallyBetweenRetriedAttempts() {
        // stageRetryAttempts=2 -> 3 total attempts -> two backoffs, of 1x and 2x the base delay —
        // a real elapsed-time assertion, not a mocked Clock, so it directly proves the loop is
        // actually sleeping rather than retrying immediately.
        Instant start = Instant.now();

        assertThatThrownBy(() -> runner.run(WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> {
                    throw new ProviderTimeoutException("lmstudio", Duration.ofSeconds(30));
                }))
                .isInstanceOf(StageFailedException.class);

        Duration elapsed = Duration.between(start, Instant.now());
        assertThat(elapsed).isGreaterThanOrEqualTo(BASE_DELAY.plus(BASE_DELAY.multipliedBy(2)));
    }

    @Test
    void nonRetryableFailureFailsOnFirstAttemptWithoutBackoff() {
        AtomicInteger calls = new AtomicInteger();
        Instant start = Instant.now();

        assertThatThrownBy(() -> runner.run(WorkflowRunId.generate(), 0, "synthesise", Map.of(), null, () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("malformed response");
                }))
                .isInstanceOf(StageFailedException.class)
                .hasCauseInstanceOf(IllegalStateException.class);

        assertThat(calls).hasValue(1);
        // The call count above is the direct proof no retry happened; this is a generous secondary
        // check that no deliberate backoff sleep occurred either. A tight bound tied to BASE_DELAY
        // (50ms) is genuinely flaky under CI-runner load — plain JVM/test-harness overhead alone hit
        // 74ms in a real CI run with no backoff involved at all — so this uses a fixed, comfortably
        // large ceiling instead of one sized to the sleep duration it's trying to rule out.
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        WorkflowStep persisted = onlyStep();
        assertThat(persisted.status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(persisted.attempts()).isEqualTo(1);
    }

    /** Post-roadmap review B2 (issue #26): {@code stageRetryAttempts = 0} means "no retries beyond
     * the first attempt," not "the loop never runs" — {@code totalAttempts} used to be
     * {@code 1 + stageRetryAttempts} with no floor, so this boundary alone didn't NPE (it's exactly
     * 1), but it's the edge the negative case below silently fell past. */
    @Test
    void zeroRetryAttemptsRunsExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        FakeWorkflowStepRepository repository = new FakeWorkflowStepRepository();
        StageRunner zeroRetryRunner = new StageRunner(
                repository, new WorkflowsProperties(true, 1, 1, 1, 0, Duration.ofSeconds(5), BASE_DELAY), metrics);

        assertThatThrownBy(() -> zeroRetryRunner.run(WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> {
                    calls.incrementAndGet();
                    throw new ProviderTimeoutException("lmstudio", Duration.ofSeconds(30));
                }))
                .isInstanceOf(StageFailedException.class);

        assertThat(calls).hasValue(1);
    }

    /** A negative {@code stage-retry-attempts} used to make {@code totalAttempts < 1}, so the retry
     * loop's body never ran at all: {@code lastError} stayed {@code null} and the line dereferencing
     * it threw {@code NullPointerException} instead of a clear {@code StageFailedException}. Nothing
     * validates this property (post-roadmap review B3 covers that separately); {@code StageRunner}
     * now clamps defensively so a negative value degrades to "one attempt, no retries" instead of
     * crashing. */
    @Test
    void negativeRetryAttemptsClampsToOneAttemptWithoutNpe() {
        AtomicInteger calls = new AtomicInteger();
        FakeWorkflowStepRepository repository = new FakeWorkflowStepRepository();
        StageRunner negativeRetryRunner = new StageRunner(
                repository, new WorkflowsProperties(true, 1, 1, 1, -5, Duration.ofSeconds(5), BASE_DELAY), metrics);

        assertThatThrownBy(
                        () -> negativeRetryRunner.run(WorkflowRunId.generate(), 0, "retrieve", Map.of(), null, () -> {
                            calls.incrementAndGet();
                            throw new ProviderTimeoutException("lmstudio", Duration.ofSeconds(30));
                        }))
                .isInstanceOf(StageFailedException.class)
                .hasCauseInstanceOf(ProviderTimeoutException.class);

        assertThat(calls).hasValue(1);
    }

    /** Post-roadmap review B2's second half: a bare exception with no message (a raw
     * {@code NullPointerException} is the common real case) must not persist the literal string
     * {@code "null"} as its recorded error — indistinguishable from a real error that says so. */
    @Test
    void exceptionWithNoMessagePersistsClassNameNotTheLiteralStringNull() {
        assertThatThrownBy(() -> runner.run(WorkflowRunId.generate(), 0, "synthesise", Map.of(), null, () -> {
                    throw new NullPointerException();
                }))
                .isInstanceOf(StageFailedException.class);

        WorkflowStep persisted = onlyStep();
        assertThat(persisted.output()).containsEntry("error", "NullPointerException");
    }

    private WorkflowStep onlyStep() {
        return stepRepository.findAll().stream()
                .reduce((a, b) -> {
                    throw new IllegalStateException("expected exactly one step, found more than one");
                })
                .orElseThrow(() -> new IllegalStateException("expected exactly one step, found none"));
    }
}
