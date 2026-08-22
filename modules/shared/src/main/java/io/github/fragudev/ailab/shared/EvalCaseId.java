package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for an {@code EvalCase}. */
public record EvalCaseId(UUID value) {

    public static EvalCaseId generate() {
        return new EvalCaseId(UUID.randomUUID());
    }

    public static EvalCaseId of(UUID value) {
        return new EvalCaseId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
