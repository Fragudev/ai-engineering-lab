package io.github.fragudev.ailab.tools;

/** Mirrors {@code tool_invocation.outcome} in the data model (docs/architecture.md #4, #7). Invalid
 * arguments and any other execution failure both fold into {@code ERROR} — the docs don't name a
 * fifth value, and the structured validation detail is carried separately in the result payload. */
public enum ToolCallOutcome {
    OK,
    TIMEOUT,
    DENIED,
    ERROR
}
