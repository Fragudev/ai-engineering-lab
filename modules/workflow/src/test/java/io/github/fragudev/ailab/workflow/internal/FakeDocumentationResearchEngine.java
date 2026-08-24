package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** A hand-written fake {@link DocumentationResearchEngine} — no mocking framework in this codebase
 * (AGENTS.md). {@code run} is public and non-final on the real class specifically so a test double
 * can override it without needing any of its eleven real collaborators (RAG pipeline, LLM-backed
 * planner/extractor/synthesiser/checker, ...), matching {@code FakeChatProvider}'s own style.
 *
 * <p>On {@link #run}, marks the run {@code SUCCEEDED} in the given {@link FakeWorkflowRunRepository}
 * — standing in for what the real engine does at the end of a successful pass — so
 * {@code WorkflowResumerTest} can prove a second {@code resumeAll()} call sees a live, already-
 * terminal status and does not resubmit the same run. */
class FakeDocumentationResearchEngine extends DocumentationResearchEngine {

    private final FakeWorkflowRunRepository runRepository;
    private final List<WorkflowRunId> runCalls = new ArrayList<>();

    FakeDocumentationResearchEngine(FakeWorkflowRunRepository runRepository) {
        super(null, null, null, null, null, null, null, null, null, null, null);
        this.runRepository = runRepository;
    }

    @Override
    public void run(WorkflowRunId runId) {
        runCalls.add(runId);
        runRepository.findById(runId.value()).ifPresent(run -> run.markSucceeded(Map.of()));
    }

    List<WorkflowRunId> runCalls() {
        return runCalls;
    }
}
