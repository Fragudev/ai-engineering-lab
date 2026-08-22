package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolCallRequest;
import java.util.UUID;
import tools.jackson.databind.ObjectMapper;

/** The payload of the SSE `tool_call` event — the model's intent to call a tool, before
 * authorization/execution. */
record ToolCallEvent(UUID callId, String toolName, Object arguments) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ToolCallEvent from(ToolCallRequest request) {
        return new ToolCallEvent(
                request.callId(), request.toolName(), JSON.readValue(request.argumentsJson(), Object.class));
    }
}
