package io.github.fragudev.ailab;

import io.github.fragudev.ailab.shared.WorkflowRunId;
import io.github.fragudev.ailab.workflow.WorkflowRunSnapshot;
import io.github.fragudev.ailab.workflow.WorkflowService;
import io.github.fragudev.ailab.workflow.WorkflowType;
import java.net.URI;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowsController {

    private final WorkflowService workflowService;

    WorkflowsController(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @PostMapping("/{type}/runs")
    ResponseEntity<WorkflowRunResponse> startRun(@PathVariable String type, @RequestBody WorkflowRunRequest request) {
        WorkflowType workflowType = WorkflowType.fromSlug(type);
        WorkflowRunId runId =
                switch (workflowType) {
                    case DOCUMENTATION_RESEARCH -> workflowService.startDocumentationResearch(request.query());
                };
        WorkflowRunSnapshot snapshot = workflowService
                .findRun(runId)
                .orElseThrow(() -> new IllegalStateException("Run vanished immediately after being created"));
        return ResponseEntity.accepted()
                .location(URI.create("/api/v1/workflows/runs/" + runId))
                .body(WorkflowRunResponse.from(snapshot));
    }

    @GetMapping("/runs/{id}")
    WorkflowRunResponse getRun(@PathVariable UUID id) {
        return workflowService
                .findRun(WorkflowRunId.of(id))
                .map(WorkflowRunResponse::from)
                .orElseThrow(() -> new NoSuchElementException("Workflow run not found: " + id));
    }
}
