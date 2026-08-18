package io.github.fragudev.ailab.shared;

import java.util.UUID;

public record IngestionJobId(UUID value) {

    public static IngestionJobId generate() {
        return new IngestionJobId(UUID.randomUUID());
    }

    public static IngestionJobId of(UUID value) {
        return new IngestionJobId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
