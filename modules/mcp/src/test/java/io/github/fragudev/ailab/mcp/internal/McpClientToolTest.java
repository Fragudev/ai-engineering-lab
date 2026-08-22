package io.github.fragudev.ailab.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolResult;
import io.modelcontextprotocol.spec.McpSchema;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Exercises {@link McpClientTool}'s pure schema/result-mapping helpers directly — no fake
 * {@code McpSyncClient} needed (no precedent for hand-faking a large third-party interface in this
 * codebase; see docs/adr/0011-mcp-tool-exposure-boundaries.md). */
class McpClientToolTest {

    private static final McpProperties.Client PROPERTIES =
            new McpProperties.Client("mcp:external", Duration.ofSeconds(10));

    @Test
    void mapsAnMcpToolIntoAToolDefinitionWithThePrefixedNameAndAlwaysRequiresConfirmation() {
        McpSchema.Tool mcpTool = McpSchema.Tool.builder("echo")
                .description("Echoes its input")
                .inputSchema(Map.of("type", "object"))
                .build();

        ToolDefinition definition = McpClientTool.toDefinition(mcpTool, "mcp:self:echo", PROPERTIES);

        assertThat(definition.name()).isEqualTo("mcp:self:echo");
        assertThat(definition.description()).isEqualTo("Echoes its input");
        assertThat(definition.requiredScopes()).containsExactly("mcp:external");
        assertThat(definition.alwaysRequiresConfirmation()).isTrue();
        assertThat(definition.introducesRetrievedContent()).isFalse();
        assertThat(definition.timeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(definition.inputSchemaJson()).contains("\"type\":\"object\"");
    }

    @Test
    void toleratesAMissingDescription() {
        // McpSchema.Tool.Builder itself defaults an unset inputSchema to {"type":"object"} rather
        // than leaving it null (confirmed against the real SDK, not assumed) — description is the
        // one field that genuinely comes back null.
        McpSchema.Tool mcpTool = McpSchema.Tool.builder("bare").build();

        ToolDefinition definition = McpClientTool.toDefinition(mcpTool, "mcp:self:bare", PROPERTIES);

        assertThat(definition.description()).isEmpty();
        assertThat(definition.inputSchemaJson()).contains("\"type\":\"object\"");
    }

    @Test
    void successfulCallResultBecomesAnOkToolResult() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder("42").build()))
                .isError(false)
                .build();

        ToolResult toolResult = McpClientTool.toToolResult(result);

        assertThat(toolResult.success()).isTrue();
        assertThat(toolResult.resultJson()).isEqualTo("42");
    }

    @Test
    void erroredCallResultBecomesAFailedToolResult() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(McpSchema.TextContent.builder("boom").build()))
                .isError(true)
                .build();

        ToolResult toolResult = McpClientTool.toToolResult(result);

        assertThat(toolResult.success()).isFalse();
        assertThat(toolResult.errorMessage()).isEqualTo("boom");
    }

    @Test
    void joinsMultipleTextContentBlocksWithNewlines() {
        McpSchema.CallToolResult result = McpSchema.CallToolResult.builder()
                .content(List.of(
                        McpSchema.TextContent.builder("first").build(),
                        McpSchema.TextContent.builder("second").build()))
                .isError(false)
                .build();

        ToolResult toolResult = McpClientTool.toToolResult(result);

        assertThat(toolResult.resultJson()).isEqualTo("first\nsecond");
    }
}
