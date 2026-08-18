package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code Citation}, so it can't be confused with a {@link MessageId}. */
public record CitationId(UUID value) {

    public static CitationId generate() {
        return new CitationId(UUID.randomUUID());
    }

    public static CitationId of(UUID value) {
        return new CitationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
