package io.github.fragudev.ailab.tools;

import io.github.fragudev.ailab.aiprovider.ChatResponse;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One element of a tool-calling-aware chat turn, produced by {@link ToolCallingChatService} —
 * mirrors {@code ai-provider}'s {@code ChatChunk} idiom exactly (static factories, boolean
 * {@code last} + nullable {@code aggregate}), extended with tool-event slots the same way
 * {@code rag.RagAnswerChunk} extends it with a citation slot. Deliberately a new sibling type, not
 * an extension of {@code ChatChunk} itself — {@code ChatChunk} has no business knowing about tool
 * calls any more than it does citations (docs/adr/0008-rag-pipeline-architecture.md's precedent for
 * exactly this fork, docs/adr/0009-tool-design-and-security-boundaries.md).
 *
 * <p>At most one of {@code deltaContent} (non-empty), {@code toolCall}, {@code toolResult} or
 * {@code pendingConfirmation} is meaningful per non-terminal chunk. {@code toolInvocations} is
 * non-empty only on the terminal chunk — every tool call resolved during the turn, in order.
 */
public record ToolChatChunk(
        String deltaContent,
        @Nullable ToolCallRequest toolCall,
        @Nullable ToolCallResult toolResult,
        @Nullable ToolCallConfirmationRequest pendingConfirmation,
        boolean last,
        @Nullable ChatResponse aggregate,
        List<ToolCallResult> toolInvocations) {

    public static ToolChatChunk delta(String text) {
        return new ToolChatChunk(text, null, null, null, false, null, List.of());
    }

    public static ToolChatChunk toolCall(ToolCallRequest request) {
        return new ToolChatChunk("", request, null, null, false, null, List.of());
    }

    public static ToolChatChunk toolResult(ToolCallResult result) {
        return new ToolChatChunk("", null, result, null, false, null, List.of());
    }

    public static ToolChatChunk pending(ToolCallConfirmationRequest request) {
        return new ToolChatChunk("", null, null, request, false, null, List.of());
    }

    public static ToolChatChunk last(ChatResponse aggregate, List<ToolCallResult> toolInvocations) {
        return new ToolChatChunk("", null, null, null, true, aggregate, toolInvocations);
    }
}
