package io.github.fragudev.ailab.shared;

import java.util.UUID;

public record DocumentId(UUID value) {

    public static DocumentId generate() {
        return new DocumentId(UUID.randomUUID());
    }

    public static DocumentId of(UUID value) {
        return new DocumentId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
