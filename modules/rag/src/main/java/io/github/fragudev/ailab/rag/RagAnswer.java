package io.github.fragudev.ailab.rag;

import io.github.fragudev.ailab.aiprovider.TokenUsage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;

/** A complete RAG turn — the generated answer (citation markers already stripped) plus every
 * citation actually resolved from it. Mirrors {@code ChatResponse}'s shape (ai-provider). */
public record RagAnswer(
        String content,
        List<RagCitationResult> citations,
        String model,
        TokenUsage usage,
        Duration latency,
        BigDecimal estimatedCostUsd) {}
