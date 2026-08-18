package io.github.fragudev.ailab.aiprovider;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * A complete assistant turn. {@code estimatedCostUsd} is a real number, never invented: it is
 * {@link BigDecimal#ZERO} for every adapter that has no pricing data (local compute, fixtures), and
 * only ever non-zero once an adapter is wired to a real, documented price list.
 */
public record ChatResponse(String content, String model, TokenUsage usage, Duration latency, BigDecimal estimatedCostUsd) {}
