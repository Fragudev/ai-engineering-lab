package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for an {@code EvalResult}. */
public record EvalResultId(UUID value) {

    public static EvalResultId generate() {
        return new EvalResultId(UUID.randomUUID());
    }

    public static EvalResultId of(UUID value) {
        return new EvalResultId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
