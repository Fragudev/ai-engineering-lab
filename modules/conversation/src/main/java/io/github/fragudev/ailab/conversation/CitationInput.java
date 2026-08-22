package io.github.fragudev.ailab.conversation;

import io.github.fragudev.ailab.shared.DocumentId;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** What {@link ConversationService#recordAssistantAnswer} needs to persist one citation — this
 * module doesn't depend on {@code rag}, so the caller (the {@code app} composition root) translates
 * {@code rag.RagCitationResult} into this shape. */
public record CitationInput(
        UUID chunkId,
        DocumentId documentId,
        double score,
        @Nullable String quotedSpan) {}
