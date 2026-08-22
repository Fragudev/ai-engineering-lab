package io.github.fragudev.ailab.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Collects every {@link Tool} bean on the classpath — built-in tools live in {@code tools.internal};
 * tools needing a different domain module (e.g. knowledge-base-search) are registered from
 * {@code app} instead (docs/adr/0009-tool-design-and-security-boundaries.md) but are picked up here
 * the same way, since Spring's component scan is rooted above every module's base package.
 */
@Service
public class ToolRegistry {

    private final Map<String, Tool> toolsByName;

    public ToolRegistry(List<Tool> tools) {
        Map<String, Tool> byName = new LinkedHashMap<>();
        for (Tool tool : tools) {
            String name = tool.definition().name();
            if (byName.containsKey(name)) {
                throw new IllegalStateException("Duplicate tool name registered: '%s'".formatted(name));
            }
            byName.put(name, tool);
        }
        this.toolsByName = Map.copyOf(byName);
    }

    public List<ToolDefinition> definitions() {
        return toolsByName.values().stream().map(Tool::definition).toList();
    }

    public Optional<Tool> find(String name) {
        return Optional.ofNullable(toolsByName.get(name));
    }
}
