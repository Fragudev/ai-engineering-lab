package io.github.fragudev.ailab.rag;

import org.jspecify.annotations.Nullable;

/**
 * One element of a streamed RAG turn — mirrors {@code ChatChunk}'s established shape (ai-provider),
 * extended with a citation slot. Exactly one of {@code deltaContent} (non-empty) or
 * {@code citation} (non-null) is meaningful per non-terminal chunk; citations are discrete events,
 * never embedded as markers in {@code deltaContent} (docs/architecture.md #5).
 */
public record RagAnswerChunk(
        String deltaContent,
        @Nullable RagCitationResult citation,
        boolean last,
        @Nullable RagAnswer aggregate) {

    public static RagAnswerChunk delta(String text) {
        return new RagAnswerChunk(text, null, false, null);
    }

    public static RagAnswerChunk citation(RagCitationResult citation) {
        return new RagAnswerChunk("", citation, false, null);
    }

    public static RagAnswerChunk last(RagAnswer aggregate) {
        return new RagAnswerChunk("", null, true, aggregate);
    }
}
