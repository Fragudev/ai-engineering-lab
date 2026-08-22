package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.workflow.WorkflowRunStatus;
import java.util.List;
import java.util.concurrent.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Real, testable resumability (docs/roadmap.md's Phase 6 acceptance criterion 1): on startup, every
 * run an interrupted process left {@code PENDING} or {@code RUNNING} is re-driven through {@link
 * DocumentationResearchEngine#run}, which is idempotent by design — it picks up from whichever steps
 * are already persisted, whether this is a first run or a resume. Split into a directly-callable
 * {@link #resumeAll()} (used both by the {@code ApplicationReadyEvent} listener and by tests, which
 * exercise the exact same resume path without needing a real process restart) and the listener
 * itself.
 */
@Component
class WorkflowResumer {

    private static final Logger log = LoggerFactory.getLogger(WorkflowResumer.class);
    private static final List<WorkflowRunStatus> RESUMABLE =
            List.of(WorkflowRunStatus.PENDING, WorkflowRunStatus.RUNNING);

    private final WorkflowRunRepository runRepository;
    private final DocumentationResearchEngine engine;
    private final ExecutorService executor;

    WorkflowResumer(WorkflowRunRepository runRepository, DocumentationResearchEngine engine, ExecutorService executor) {
        this.runRepository = runRepository;
        this.engine = engine;
        this.executor = executor;
    }

    @EventListener(ApplicationReadyEvent.class)
    void onApplicationReady() {
        resumeAll();
    }

    void resumeAll() {
        List<WorkflowRun> resumable = runRepository.findByStatusIn(RESUMABLE);
        if (resumable.isEmpty()) {
            return;
        }
        log.info("Resuming {} workflow run(s) left in-flight by a prior process", resumable.size());
        for (WorkflowRun run : resumable) {
            var runId = run.id();
            executor.submit(() -> engine.run(runId));
        }
    }
}
