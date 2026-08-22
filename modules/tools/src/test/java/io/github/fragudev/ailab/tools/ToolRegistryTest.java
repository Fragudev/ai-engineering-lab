package io.github.fragudev.ailab.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ToolRegistryTest {

    @Test
    void findsAndListsToolsPresentAtConstruction() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool("calculator")));

        assertThat(registry.find("calculator")).isPresent();
        assertThat(registry.definitions()).extracting(ToolDefinition::name).containsExactly("calculator");
    }

    @Test
    void constructorRejectsDuplicateNames() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(fakeTool("calculator"), fakeTool("calculator"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calculator");
    }

    @Test
    void registerAddsAToolDiscoveredAfterConstruction() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool("calculator")));

        registry.register(fakeTool("mcp:self:echo"));

        assertThat(registry.find("mcp:self:echo")).isPresent();
        assertThat(registry.definitions())
                .extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("calculator", "mcp:self:echo");
    }

    @Test
    void registerRejectsADuplicateName() {
        ToolRegistry registry = new ToolRegistry(List.of(fakeTool("calculator")));

        assertThatThrownBy(() -> registry.register(fakeTool("calculator")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("calculator");
    }

    @Test
    void findReturnsEmptyForAnUnknownTool() {
        ToolRegistry registry = new ToolRegistry(List.of());

        assertThat(registry.find("unknown")).isEmpty();
    }

    private static Tool fakeTool(String name) {
        ToolDefinition definition =
                new ToolDefinition(name, "1", "test tool", "{}", "{}", Set.of(), false, false, Duration.ofSeconds(5));
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
                return ToolResult.ok("{}");
            }
        };
    }
}
