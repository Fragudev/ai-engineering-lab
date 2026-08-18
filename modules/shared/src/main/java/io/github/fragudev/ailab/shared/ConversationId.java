package io.github.fragudev.ailab.shared;

import java.util.UUID;

/** Typed identifier for a {@code Conversation}, so it can't be confused with a {@link MessageId}. */
public record ConversationId(UUID value) {

    public static ConversationId generate() {
        return new ConversationId(UUID.randomUUID());
    }

    public static ConversationId of(UUID value) {
        return new ConversationId(value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
