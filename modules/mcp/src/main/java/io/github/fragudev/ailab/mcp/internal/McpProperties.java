package io.github.fragudev.ailab.mcp.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Only the client side is configured here — the server side (exposing this application's own tool
 * registry) is Spring AI's own {@code spring.ai.mcp.server.*} properties directly, since nothing
 * about it is this project's own concern to wrap. Whether a client connects to anything at all is
 * also Spring AI's own concern ({@code spring.ai.mcp.client.enabled}, {@code false} by default in
 * this project's shipped config — no real external MCP server exists in this project's
 * infrastructure to point at, docs/adr/0011-mcp-tool-exposure-boundaries.md): when it's off, no
 * {@code McpSyncClient} beans exist, {@link McpClientToolRegistrar}'s injected list is empty, and
 * nothing here needs its own separate on/off switch.
 *
 * @param client see {@link Client}
 */
@Validated
@ConfigurationProperties(prefix = "ai.mcp")
public record McpProperties(@NotNull @Valid Client client) {

    /**
     * @param requiredScope stands in for "the one authenticated user's permissions" for every
     *     externally-sourced tool, the same single-config-list convention as
     *     {@code ai.tools.granted-scopes}
     * @param defaultTimeout the timeout given to every MCP-client-sourced {@code ToolDefinition}
     */
    public record Client(
            @NotBlank String requiredScope, @NotNull Duration defaultTimeout) {}
}
