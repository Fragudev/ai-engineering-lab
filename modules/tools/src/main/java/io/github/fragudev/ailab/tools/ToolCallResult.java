package io.github.fragudev.ailab.tools;

import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** The outcome of one tool call, in a shape lightweight enough for the SSE stream and for
 * accumulating into {@code RagAnswer.toolInvocations()} — carries {@code argumentsJson} too so
 * {@link ToolInvoker#recordForMessage} can persist it standalone, once the owning message exists,
 * without the caller needing to track arguments separately alongside each result. */
public record ToolCallResult(
        UUID callId,
        String toolName,
        String argumentsJson,
        ToolCallOutcome outcome,
        @Nullable String resultJson,
        @Nullable String message,
        long durationMs) {}
