package io.github.fragudev.ailab.tools;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/**
 * Collects every {@link Tool} bean on the classpath — built-in tools live in {@code tools.internal};
 * tools needing a different domain module (e.g. knowledge-base-search) are registered from
 * {@code app} instead (docs/adr/0009-tool-design-and-security-boundaries.md) but are picked up here
 * the same way, since Spring's component scan is rooted above every module's base package.
 *
 * <p>{@link #register} exists for tools that can't be known at construction time — an MCP client's
 * discovered-at-runtime external tools (Phase 7, docs/adr/0011-mcp-tool-exposure-boundaries.md), added
 * once the client's own handshake completes rather than blocking application startup on it.
 */
@Service
public class ToolRegistry {

    private final Map<String, Tool> toolsByName;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> byName = new ConcurrentHashMap<>();
        for (Tool tool : tools) {
            String name = tool.definition().name();
            if (byName.putIfAbsent(name, tool) != null) {
                throw new IllegalStateException("Duplicate tool name registered: '%s'".formatted(name));
            }
        }
        this.toolsByName = byName;
    }

    public List<ToolDefinition> definitions() {
        return toolsByName.values().stream().map(Tool::definition).toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }

    public void register(Tool tool) {
        String name = tool.definition().name();
        if (toolsByName.putIfAbsent(name, tool) != null) {
            throw new IllegalStateException("Duplicate tool name registered: '%s'".formatted(name));
        }
    }
}
