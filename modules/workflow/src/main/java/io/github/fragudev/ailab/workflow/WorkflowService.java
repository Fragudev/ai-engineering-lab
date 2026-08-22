package io.github.fragudev.ailab.workflow;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.workflow.internal.DocumentationResearchEngine;
import io.github.fragudev.ailab.workflow.internal.WorkflowRun;
import io.github.fragudev.ailab.workflow.internal.WorkflowRunRepository;
import io.github.fragudev.ailab.workflow.internal.WorkflowStep;
import io.github.fragudev.ailab.workflow.internal.WorkflowStepRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import org.springframework.stereotype.Service;

/**
 * The public façade for starting and inspecting agentic workflow runs. {@code workflow} owns its
 * own run loop internally (mirroring {@code tools.ToolCallingChatService}'s precedent, not {@code
 * app} composing {@code rag}+{@code conversation} as peers) — {@code app} stays a thin caller: start
 * a run, read a run back (docs/adr/0010-agent-orchestration.md).
 */
@Service
public class WorkflowService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowStepRepository stepRepository;
    private final DocumentationResearchEngine engine;
    private final ExecutorService executor;

    public WorkflowService(
            WorkflowRunRepository runRepository,
            WorkflowStepRepository stepRepository,
            DocumentationResearchEngine engine,
            ExecutorService executor) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.engine = engine;
        this.executor = executor;
    }

    /** Persists the run ({@code PENDING}) and returns immediately — matching the async, 202+Location
     * pattern already used for document upload (docs/architecture.md #2, #5). Execution happens on a
     * background thread; {@link #findRun} is how the caller observes progress. */
    public WorkflowRunId startDocumentationResearch(String query) {
        WorkflowRunId runId = WorkflowRunId.generate();
        WorkflowRun run =
                new WorkflowRun(runId, WorkflowType.DOCUMENTATION_RESEARCH, Map.of("query", query), UUID.randomUUID());
        runRepository.save(run);
        executor.submit(() -> engine.run(runId));
        return runId;
    }

    public Optional<WorkflowRunSnapshot> findRun(WorkflowRunId id) {
        return runRepository.findById(id.value()).map(this::toSnapshot);
    }

    private WorkflowRunSnapshot toSnapshot(WorkflowRun run) {
        List<WorkflowStepSnapshot> steps = stepRepository.findByRun(run.id()).stream()
                .map(WorkflowService::toSnapshot)
                .toList();
        return new WorkflowRunSnapshot(
                run.id(),
                run.type(),
                run.status(),
                run.input(),
                run.output(),
                run.correlationId(),
                steps,
                run.createdAt(),
                run.updatedAt());
    }

    private static WorkflowStepSnapshot toSnapshot(WorkflowStep step) {
        return new WorkflowStepSnapshot(
                step.id(),
                step.stepIndex(),
                step.name(),
                step.status(),
                step.input(),
                step.output(),
                step.attempts(),
                step.costUsd(),
                step.createdAt(),
                step.updatedAt());
    }
}
