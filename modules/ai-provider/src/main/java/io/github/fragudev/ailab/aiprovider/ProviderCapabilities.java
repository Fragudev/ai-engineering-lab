package io.github.fragudev.ailab.aiprovider;

/**
 * What the currently active model actually supports, so callers can degrade explicitly instead of
 * failing mysteriously (docs/adr/0004-ai-provider-abstraction.md). {@code tools} (Phase 5) queries
 * {@code supportsNativeToolCalling} for its structured-output fallback.
 */
public record ProviderCapabilities(
        boolean supportsNativeToolCalling, boolean supportsStructuredOutput, int contextWindowTokens) {}
