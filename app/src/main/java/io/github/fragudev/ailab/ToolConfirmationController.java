package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolConfirmationService;
import java.util.NoSuchElementException;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Resolves a pending tool-call confirmation raised mid-stream by a RAG-context turn
 * (docs/threat-model.md T2) — not part of architecture.md #5's original path list; added in Phase 5
 * once the confirmation flow it names was actually designed down to an endpoint. */
@RestController
@RequestMapping("/api/v1/tool-calls")
class ToolConfirmationController {

    private final ToolConfirmationService toolConfirmationService;

    ToolConfirmationController(ToolConfirmationService toolConfirmationService) {
        this.toolConfirmationService = toolConfirmationService;
    }

    @PostMapping("/{callId}:confirm")
    void confirm(@PathVariable UUID callId, @RequestBody ToolConfirmRequest request) {
        boolean resolved = toolConfirmationService.confirm(callId, request.approved());
        if (!resolved) {
            throw new NoSuchElementException("No pending tool call confirmation for id " + callId);
        }
    }
}
