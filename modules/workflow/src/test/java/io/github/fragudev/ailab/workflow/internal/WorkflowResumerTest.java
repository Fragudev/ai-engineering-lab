package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.workflow.WorkflowType;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Post-roadmap review issue #31: {@code WorkflowResumer} had no tests despite being where Phase 6's
 * headline "resumable" claim actually lives. {@link FakeWorkflowRunRepository} and
 * {@link FakeDocumentationResearchEngine} stand in for the real JPA repository and the real
 * eleven-dependency engine — no mocking framework in this codebase (AGENTS.md).
 */
class WorkflowResumerTest {

    private final FakeWorkflowRunRepository runRepository = new FakeWorkflowRunRepository();
    private final FakeDocumentationResearchEngine engine = new FakeDocumentationResearchEngine(runRepository);
    private final WorkflowResumer resumer = new WorkflowResumer(runRepository, engine, new DirectExecutorService());

    @Test
    void resumesARunLeftRunningByAnInterruptedProcess() {
        WorkflowRun run = newRun();
        run.markRunning();
        runRepository.save(run);

        resumer.resumeAll();

        assertThat(engine.runCalls()).containsExactly(run.id());
    }

    @Test
    void resumesARunStillPendingThatNeverStarted() {
        WorkflowRun run = newRun();
        runRepository.save(run);

        resumer.resumeAll();

        assertThat(engine.runCalls()).containsExactly(run.id());
    }

    @Test
    void doesNotResumeARunThatIsAlreadyTerminal() {
        WorkflowRun succeeded = newRun();
        succeeded.markSucceeded(Map.of());
        runRepository.save(succeeded);

        WorkflowRun failed = newRun();
        failed.markFailed(Map.of());
        runRepository.save(failed);

        resumer.resumeAll();

        assertThat(engine.runCalls()).isEmpty();
    }

    @Test
    void resumingTwiceDoesNotDuplicateWorkForARunThatHasSinceFinished() {
        WorkflowRun run = newRun();
        run.markRunning();
        runRepository.save(run);

        resumer.resumeAll();
        // The fake engine marks the run SUCCEEDED as part of "running" it, exactly like the real
        // engine's own run() does on completion — so the second call re-queries live status rather
        // than acting on a stale snapshot from the first call.
        resumer.resumeAll();

        assertThat(engine.runCalls()).containsExactly(run.id());
    }

    @Test
    void doesNothingWhenNoRunsAreResumable() {
        resumer.resumeAll();

        assertThat(engine.runCalls()).isEmpty();
    }

    private static WorkflowRun newRun() {
        return new WorkflowRun(
                WorkflowRunId.generate(),
                WorkflowType.DOCUMENTATION_RESEARCH,
                Map.of("query", "ignored"),
                UUID.randomUUID());
    }
}
