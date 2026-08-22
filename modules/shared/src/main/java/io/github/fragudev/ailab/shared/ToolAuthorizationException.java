package io.github.fragudev.ailab.shared;

import java.util.Set;

/** A tool call was rejected because the caller lacks a required scope. Maps to HTTP 403 — a scope
 * denial is checked before execution, never a silent failure (docs/architecture.md #13). */
public class ToolAuthorizationException extends RuntimeException {

    public ToolAuthorizationException(String toolName, Set<String> missingScopes) {
        super("Tool '%s' requires scope(s) %s, which are not granted".formatted(toolName, missingScopes));
    }
}
