package io.github.fragudev.ailab;

import io.github.fragudev.ailab.aiprovider.ChatRole;
import io.github.fragudev.ailab.conversation.Citation;
import io.github.fragudev.ailab.conversation.Message;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

record MessageResponse(
        UUID id,
        UUID conversationId,
        ChatRole role,
        String content,
        @Nullable String model,
        @Nullable Integer promptTokens,
        @Nullable Integer completionTokens,
        @Nullable Long latencyMs,
        @Nullable BigDecimal estimatedCostUsd,
        List<CitationResponse> citations,
        Instant createdAt) {

    static MessageResponse from(Message message, List<Citation> citations) {
        return new MessageResponse(
                message.id().value(),
                message.conversationId().value(),
                message.role(),
                message.content(),
                message.model(),
                message.promptTokens(),
                message.completionTokens(),
                message.latencyMs(),
                message.estimatedCostUsd(),
                citations.stream().map(CitationResponse::from).toList(),
                message.createdAt());
    }
}
