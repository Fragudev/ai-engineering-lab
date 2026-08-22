package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code ToolInvocation}, so it can't be confused with a {@link MessageId}. */
public record ToolInvocationId(UUID value) {

    public static ToolInvocationId generate() {
        return new ToolInvocationId(UUID.randomUUID());
    }

    public static ToolInvocationId of(UUID value) {
        return new ToolInvocationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
