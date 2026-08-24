package io.github.fragudev.ailab.tools.internal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * @param grantedScopes stands in for "the one authenticated user's permissions" — no real principal
 *     exists in this codebase yet (docs/architecture.md #13's "design" framing for authentication;
 *     single-user, no multi-tenancy per AGENTS.md). See docs/adr/0009-tool-design-and-security-boundaries.md.
 * @param defaultTimeout used when a {@code ToolDefinition} doesn't override it
 * @param confirmationTimeout how long a RAG-sourced tool call waits for {@code POST
 *     /api/v1/tool-calls/{callId}:confirm} before resolving to {@code TIMEOUT} — distinct from a
 *     tool's own execution timeout
 * @param maxCallsPerTurn bounds the tool-calling loop; not a general agentic loop (Phase 6's job)
 * @param maxPendingConfirmations caps how many tool calls may be simultaneously awaiting {@code
 *     POST /api/v1/tool-calls/{callId}:confirm} at once — a minor denial-of-service consideration
 *     given the absence of rate limiting (post-roadmap review B4); a reasoned starting bound, not
 *     measured against any dataset (AGENTS.md rule 2)
 */
@Validated
@ConfigurationProperties(prefix = "ai.tools")
public record ToolsProperties(
        boolean enabled,
        @NotNull List<String> grantedScopes,
        @NotNull Duration defaultTimeout,
        @NotNull Duration confirmationTimeout,
        @Positive int maxCallsPerTurn,
        @Positive int maxPendingConfirmations) {}
