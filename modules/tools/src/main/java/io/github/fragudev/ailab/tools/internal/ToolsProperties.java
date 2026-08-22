package io.github.fragudev.ailab.tools.internal;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param grantedScopes stands in for "the one authenticated user's permissions" — no real principal
 *     exists in this codebase yet (docs/architecture.md #13's "design" framing for authentication;
 *     single-user, no multi-tenancy per AGENTS.md). See docs/adr/0009-tool-design-and-security-boundaries.md.
 * @param defaultTimeout used when a {@code ToolDefinition} doesn't override it
 * @param confirmationTimeout how long a RAG-sourced tool call waits for {@code POST
 *     /api/v1/tool-calls/{callId}:confirm} before resolving to {@code TIMEOUT} — distinct from a
 *     tool's own execution timeout
 * @param maxCallsPerTurn bounds the tool-calling loop; not a general agentic loop (Phase 6's job)
 */
@ConfigurationProperties(prefix = "ai.tools")
public record ToolsProperties(
        boolean enabled,
        List<String> grantedScopes,
        Duration defaultTimeout,
        Duration confirmationTimeout,
        int maxCallsPerTurn) {}
