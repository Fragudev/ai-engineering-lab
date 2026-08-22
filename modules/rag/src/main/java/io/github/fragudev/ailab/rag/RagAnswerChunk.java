package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.tools.ToolCallConfirmationRequest;
import io.github.fragudev.ailab.tools.ToolCallRequest;
import io.github.fragudev.ailab.tools.ToolCallResult;
import org.jspecify.annotations.Nullable;

/**
 * One element of a streamed RAG turn — mirrors {@code ChatChunk}'s established shape (ai-provider),
 * extended with a citation slot and, as of Phase 5, tool-call slots (mirroring
 * {@code tools.ToolChatChunk}, which {@link RagPipeline} now streams through). At most one of
 * {@code deltaContent} (non-empty), {@code citation}, {@code toolCall}, {@code toolResult} or
 * {@code pendingConfirmation} is meaningful per non-terminal chunk; citations and tool events are
 * discrete events, never embedded as markers in {@code deltaContent} (docs/architecture.md #5).
 */
public record RagAnswerChunk(
        String deltaContent,
        @Nullable RagCitationResult citation,
        @Nullable ToolCallRequest toolCall,
        @Nullable ToolCallResult toolResult,
        @Nullable ToolCallConfirmationRequest pendingConfirmation,
        boolean last,
        @Nullable RagAnswer aggregate) {

    public static RagAnswerChunk delta(String text) {
        return new RagAnswerChunk(text, null, null, null, null, false, null);
    }

    public static RagAnswerChunk citation(RagCitationResult citation) {
        return new RagAnswerChunk("", citation, null, null, null, false, null);
    }

    public static RagAnswerChunk toolCall(ToolCallRequest toolCall) {
        return new RagAnswerChunk("", null, toolCall, null, null, false, null);
    }

    public static RagAnswerChunk toolResult(ToolCallResult toolResult) {
        return new RagAnswerChunk("", null, null, toolResult, null, false, null);
    }

    public static RagAnswerChunk pendingConfirmation(ToolCallConfirmationRequest pendingConfirmation) {
        return new RagAnswerChunk("", null, null, null, pendingConfirmation, false, null);
    }

    public static RagAnswerChunk last(RagAnswer aggregate) {
        return new RagAnswerChunk("", null, null, null, null, true, aggregate);
    }
}
