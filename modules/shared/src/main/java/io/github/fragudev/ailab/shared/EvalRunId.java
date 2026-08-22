package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for an {@code EvalRun}. */
public record EvalRunId(UUID value) {

    public static EvalRunId generate() {
        return new EvalRunId(UUID.randomUUID());
    }

    public static EvalRunId of(UUID value) {
        return new EvalRunId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
