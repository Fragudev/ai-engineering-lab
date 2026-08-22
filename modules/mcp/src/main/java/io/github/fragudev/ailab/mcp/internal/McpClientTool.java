package io.github.fragudev.ailab.mcp.internal;

import io.github.fragudev.ailab.tools.Tool;
import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Adapts one tool discovered on an external MCP server into this project's own {@link Tool} SPI, so
 * it flows through {@code ToolRegistry}/{@code ToolInvoker}/{@code ToolCallingChatService} exactly
 * like {@code CalculatorTool} or {@code app.KnowledgeBaseSearchTool} — "usable in chat, subject to
 * the same authorization and timeouts" (docs/roadmap.md, Phase 7 acceptance criterion 2), plus one
 * deliberate difference: {@link ToolDefinition#alwaysRequiresConfirmation()} is always {@code true}
 * here (docs/threat-model.md T9, docs/adr/0011-mcp-tool-exposure-boundaries.md).
 */
class McpClientTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final McpSyncClient client;
    private final McpSchema.Tool mcpTool;
    private final String prefixedName;
    private final McpProperties.Client properties;

    McpClientTool(McpSyncClient client, McpSchema.Tool mcpTool, String prefixedName, McpProperties.Client properties) {
        this.client = client;
        this.mcpTool = mcpTool;
        this.prefixedName = prefixedName;
        this.properties = properties;
    }

    @Override
    public ToolDefinition definition() {
        return toDefinition(mcpTool, prefixedName, properties);
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
        Map<String, Object> arguments = JSON.readValue(argumentsJson, MAP_TYPE);
        McpSchema.CallToolRequest request = McpSchema.CallToolRequest.builder(mcpTool.name())
                .arguments(arguments)
                .build();
        McpSchema.CallToolResult result = client.callTool(request);
        return toToolResult(result);
    }

    /** Pure — no {@link McpSyncClient} needed, so this is unit-testable without a hand-written fake
     * for that large, third-party interface (no precedent for one in this codebase). */
    static ToolDefinition toDefinition(McpSchema.Tool mcpTool, String prefixedName, McpProperties.Client properties) {
        String description = mcpTool.description() == null ? "" : mcpTool.description();
        Map<String, Object> inputSchema = mcpTool.inputSchema() == null ? Map.of() : mcpTool.inputSchema();
        return new ToolDefinition(
                prefixedName,
                "external",
                description,
                JSON.writeValueAsString(inputSchema),
                "{}",
                Set.of(properties.requiredScope()),
                false,
                true,
                properties.defaultTimeout());
    }

    /** Pure — same reasoning as {@link #toDefinition}. */
    static ToolResult toToolResult(McpSchema.CallToolResult result) {
        String text = extractText(result.content());
        return Boolean.TRUE.equals(result.isError()) ? ToolResult.failure(text) : ToolResult.ok(text);
    }

    private static String extractText(List<McpSchema.Content> content) {
        return content.stream()
                .filter(McpSchema.TextContent.class::isInstance)
                .map(McpSchema.TextContent.class::cast)
                .map(McpSchema.TextContent::text)
                .collect(Collectors.joining("\n"));
    }
}
