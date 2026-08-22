package io.github.fragudev.ailab.tools;

import io.github.fragudev.ailab.shared.ConversationId;
import org.jspecify.annotations.Nullable;

/**
 * What a {@link Tool} is told about the call it's executing. {@code conversationId} is {@code null}
 * for a direct {@code POST /api/v1/tools/{name}:invoke} call (no owning conversation).
 * {@code contextContainsRetrievedContent} is the latched value described in
 * {@link ToolCallingChatService}'s javadoc — informational for the tool itself; the confirmation
 * gate is enforced by the loop before a tool ever sees the call.
 */
public record ToolExecutionContext(@Nullable ConversationId conversationId, boolean contextContainsRetrievedContent) {

    public static ToolExecutionContext direct() {
        return new ToolExecutionContext(null, false);
    }
}
