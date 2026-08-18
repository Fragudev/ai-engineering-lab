package io.github.fragudev.ailab;

import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.rag.RagAnswer;
import java.math.BigDecimal;
import org.jspecify.annotations.Nullable;

/** The payload of the SSE `usage` event: what an answer cost, and where to find its trace. */
record UsageSummary(
        String model,
        int promptTokens,
        int completionTokens,
        long latencyMs,
        BigDecimal estimatedCostUsd,
        @Nullable String traceId) {

    static UsageSummary from(ChatResponse response, @Nullable String traceId) {
        return new UsageSummary(
                response.model(),
                response.usage().promptTokens(),
                response.usage().completionTokens(),
                response.latency().toMillis(),
                response.estimatedCostUsd(),
                traceId);
    }

    static UsageSummary from(RagAnswer answer, @Nullable String traceId) {
        return new UsageSummary(
                answer.model(),
                answer.usage().promptTokens(),
                answer.usage().completionTokens(),
                answer.latency().toMillis(),
                answer.estimatedCostUsd(),
                traceId);
    }
}
