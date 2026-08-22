package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for an {@code EvalDataset}. */
public record EvalDatasetId(UUID value) {

    public static EvalDatasetId generate() {
        return new EvalDatasetId(UUID.randomUUID());
    }

    public static EvalDatasetId of(UUID value) {
        return new EvalDatasetId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
