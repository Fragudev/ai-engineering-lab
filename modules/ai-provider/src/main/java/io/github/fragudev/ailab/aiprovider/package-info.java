/**
 * ChatProvider and EmbeddingProvider abstractions, token/cost accounting and resilience, behind project-owned interfaces (see docs/adr/0004-ai-provider-abstraction.md).
 *
 * <p>This package is the module's public API. Everything under {@code internal} is
 * implementation detail, off-limits to other modules (see AGENTS.md, Module boundaries).
 */
@org.jspecify.annotations.NullMarked
package io.github.fragudev.ailab.aiprovider;
