package io.github.fragudev.ailab.tools;

import org.jspecify.annotations.Nullable;

/**
 * What a {@link Tool} implementation returns — before {@link ToolInvoker} wraps it with the
 * outcome/timing/persistence concerns every invocation shares. A tool signals its own business
 * failure (e.g. division by zero, a malformed calculation) via {@code success = false} and a message
 * — it never throws for an expected failure mode, only lets a genuinely unexpected exception
 * propagate for {@link ToolInvoker} to catch and record as {@link ToolCallOutcome#ERROR}.
 */
public record ToolResult(
        boolean success,
        @Nullable String resultJson,
        @Nullable String errorMessage) {

    public static ToolResult ok(String resultJson) {
        return new ToolResult(true, resultJson, null);
    }

    public static ToolResult failure(String errorMessage) {
        return new ToolResult(false, null, errorMessage);
    }
}
