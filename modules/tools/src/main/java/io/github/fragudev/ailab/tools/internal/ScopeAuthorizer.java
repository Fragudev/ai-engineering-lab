package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.tools.ToolDefinition;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Checks a tool's required scopes against the config-declared granted set (docs/architecture.md
 * #13's "each tool declares required scopes, checked against the principal before invocation" — no
 * real principal exists in this codebase yet, so {@code ai.tools.granted-scopes} stands in for it;
 * see docs/adr/0009-tool-design-and-security-boundaries.md).
 */
@Component
public class ScopeAuthorizer {

    private final ToolsProperties properties;

    public ScopeAuthorizer(ToolsProperties properties) {
        this.properties = properties;
    }

    public boolean isAuthorized(ToolDefinition definition) {
        return missingScopes(definition).isEmpty();
    }

    public Set<String> missingScopes(ToolDefinition definition) {
        Set<String> granted = Set.copyOf(properties.grantedScopes());
        Set<String> missing = new LinkedHashSet<>(definition.requiredScopes());
        missing.removeAll(granted);
        return missing;
    }
}
