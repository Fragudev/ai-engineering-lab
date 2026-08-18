package io.github.fragudev.ailab;

import io.github.fragudev.ailab.conversation.Conversation;
import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record ConversationResponse(
        UUID id, @Nullable String title, @Nullable String ragProfile, Instant createdAt, Instant updatedAt) {

    static ConversationResponse from(Conversation conversation) {
        return new ConversationResponse(
                conversation.id().value(),
                conversation.title(),
                conversation.ragProfile(),
                conversation.createdAt(),
                conversation.updatedAt());
    }
}
