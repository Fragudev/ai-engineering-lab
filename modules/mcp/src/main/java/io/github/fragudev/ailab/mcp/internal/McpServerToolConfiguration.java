package io.github.fragudev.ailab.mcp.internal;

import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolInvoker;
import io.github.fragudev.ailab.tools.ToolRegistry;
import io.github.fragudev.ailab.tools.ToolResult;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Exposes this application's own tool registry as an MCP server — the "an external MCP client can
 * discover and invoke the tools" acceptance criterion (docs/roadmap.md, Phase 7). Built once, from
 * {@code toolRegistry.definitions()} at startup: this application's own calculator/mock-weather/
 * knowledge-base-search, not anything later pulled in via the MCP client — re-exposing a third
 * party's tool through this server would raise a "are we now vouching for it" trust question this
 * phase doesn't answer (docs/adr/0011-mcp-tool-exposure-boundaries.md). Every call handler delegates
 * straight to {@link ToolInvoker#invokeOrThrow}, the exact same validate→authorize→timeout→execute→
 * persist pipeline {@code POST /api/v1/tools/{name}:invoke} already uses (docs/adr/0009's own
 * forward-looking line: "the same registry without a chat context") — an external MCP client gets
 * identical scope/schema/timeout guarantees to a direct REST caller.
 */
@Configuration
class McpServerToolConfiguration {

    private static final Logger log = LoggerFactory.getLogger(McpServerToolConfiguration.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    @Bean
    List<McpServerFeatures.SyncToolSpecification> toolSpecifications(
            ToolRegistry toolRegistry, ToolInvoker toolInvoker) {
        return toolRegistry.definitions().stream()
                .map(definition -> toSpecification(definition, toolInvoker))
                .toList();
    }

    private static McpServerFeatures.SyncToolSpecification toSpecification(
            ToolDefinition definition, ToolInvoker toolInvoker) {
        // Tool.builder(String) is flagged deprecated in favor of an overload taking an McpJsonMapper
        // for schema-string convenience — not useful here, since inputSchemaJson is parsed into a
        // Map once and passed to the plain .inputSchema(Map) setter below either way.
        McpSchema.Tool tool = McpSchema.Tool.builder(definition.name())
                .description(definition.description())
                .inputSchema(toSchemaMap(definition.inputSchemaJson()))
                .build();
        return McpServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((exchange, request) -> handle(definition.name(), request, toolInvoker))
                .build();
    }

    private static McpSchema.CallToolResult handle(
            String toolName, McpSchema.CallToolRequest request, ToolInvoker toolInvoker) {
        String argumentsJson = JSON.writeValueAsString(request.arguments());
        try {
            ToolResult result = toolInvoker.invokeOrThrow(toolName, argumentsJson, ToolExecutionContext.direct());
            return McpSchema.CallToolResult.builder()
                    .content(List.of(
                            McpSchema.TextContent.builder(result.resultJson()).build()))
                    .isError(false)
                    .build();
        } catch (RuntimeException e) {
            log.warn("MCP tool call '{}' failed", toolName, e);
            return McpSchema.CallToolResult.builder()
                    .content(List.of(
                            McpSchema.TextContent.builder(e.getMessage()).build()))
                    .isError(true)
                    .build();
        }
    }

    private static Map<String, Object> toSchemaMap(String schemaJson) {
        return JSON.readValue(schemaJson, MAP_TYPE);
    }
}
