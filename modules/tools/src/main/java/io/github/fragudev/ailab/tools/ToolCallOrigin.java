package io.github.fragudev.ailab.tools;

/** Where a chat turn driving {@link ToolCallingChatService} started. {@code RAG_CONTEXT} means the
 * turn's context already contains retrieved document content when the loop begins — the case
 * docs/threat-model.md's T2 requires confirmation for. {@code PLAIN_CHAT} starts trusted, but a tool
 * that itself introduces retrieved content (see {@link ToolDefinition#introducesRetrievedContent()})
 * can still latch a plain-chat turn into requiring confirmation partway through — see
 * {@code ToolCallingChatService}'s javadoc. */
public enum ToolCallOrigin {
    PLAIN_CHAT,
    RAG_CONTEXT
}
