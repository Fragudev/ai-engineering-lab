package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolDefinition;
import java.util.Set;

record ToolDefinitionResponse(
        String name,
        String version,
        String description,
        String inputSchemaJson,
        Set<String> requiredScopes,
        long timeoutSeconds) {

    static ToolDefinitionResponse from(ToolDefinition definition) {
        return new ToolDefinitionResponse(
                definition.name(),
                definition.version(),
                definition.description(),
                definition.inputSchemaJson(),
                definition.requiredScopes(),
                definition.timeout().toSeconds());
    }
}
