package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.WorkflowRunId;
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

    private WorkflowStep onlyStep() {
        return stepRepository.findAll().stream()
                .reduce((a, b) -> {
                    throw new IllegalStateException("expected exactly one step, found more than one");
                })
                .orElseThrow(() -> new IllegalStateException("expected exactly one step, found none"));
    }
}
