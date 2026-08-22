package io.github.fragudev.ailab.shared;

import java.time.Duration;

/** A tool did not complete within its configured timeout. Maps to HTTP 504 — the same status
 * {@link ProviderTimeoutException} uses for the analogous provider-call case. */
public class ToolTimeoutException extends RuntimeException {

    public ToolTimeoutException(String toolName, Duration timeout) {
        super("Tool '%s' did not complete within %s".formatted(toolName, timeout));
    }
}
