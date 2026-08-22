package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolCallOutcome;
import io.github.fragudev.ailab.tools.ToolCallResult;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.ObjectMapper;

/** The payload of the SSE `tool_result` event. */
record ToolResultEvent(
        UUID callId,
        String toolName,
        ToolCallOutcome outcome,
        @Nullable Object result,
        @Nullable String message) {

    private static final ObjectMapper JSON = new ObjectMapper();

    static ToolResultEvent from(ToolCallResult result) {
        Object parsedResult = result.resultJson() == null ? null : JSON.readValue(result.resultJson(), Object.class);
        return new ToolResultEvent(
                result.callId(), result.toolName(), result.outcome(), parsedResult, result.message());
    }
}
