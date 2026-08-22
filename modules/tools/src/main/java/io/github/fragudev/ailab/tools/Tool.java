package io.github.fragudev.ailab.tools;

/**
 * SPI every tool implements — collected into {@link ToolRegistry} as a Spring bean. Built-in tools
 * ({@code calculator}, the mock external API) live in {@code tools.internal}; tools needing a
 * different domain module (e.g. knowledge-base-search, which needs {@code knowledge}, off limits to
 * this module per docs/architecture.md #3) are registered from {@code app} instead — see
 * docs/adr/0009-tool-design-and-security-boundaries.md.
 */
public interface Tool {

    ToolDefinition definition();

    /** @param argumentsJson already schema-validated by {@link ToolInvoker} before this is called */
    ToolResult execute(ToolExecutionContext context, String argumentsJson);
}
