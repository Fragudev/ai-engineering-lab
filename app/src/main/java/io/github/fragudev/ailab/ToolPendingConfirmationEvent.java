package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolCallConfirmationRequest;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** The payload of the SSE `tool_call_pending` event — new in Phase 5, not part of the original
 * architecture.md #5 event list, added once docs/threat-model.md T2's confirmation control was
 * actually designed down to an endpoint. The client should render an approve/reject affordance and
 * call {@code POST /api/v1/tool-calls/{callId}:confirm} within {@code confirmationTimeoutSeconds}. */
record ToolPendingConfirmationEvent(UUID callId, String toolName, Object arguments, long confirmationTimeoutSeconds) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ToolPendingConfirmationEvent from(ToolCallConfirmationRequest request) {
        return new ToolPendingConfirmationEvent(
                request.callId(),
                request.toolName(),
                JSON.readValue(request.argumentsJson(), Object.class),
                request.confirmationTimeout().toSeconds());
    }
}
