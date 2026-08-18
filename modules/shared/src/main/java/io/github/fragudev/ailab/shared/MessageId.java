package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code Message}, so it can't be confused with a {@link ConversationId}. */
public record MessageId(UUID value) {

    public static MessageId generate() {
        return new MessageId(UUID.randomUUID());
    }

    public static MessageId of(UUID value) {
        return new MessageId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
