package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.tools.Tool;
import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import java.time.Duration;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The roadmap's "mock external API" — a stand-in for a real weather lookup. Deterministic and
 * hash-seeded per city (same idiom as {@code aiprovider.internal.RecordedEmbeddingProvider}), and
 * performs no real network egress, so docs/threat-model.md T4 (SSRF) is structurally moot for this
 * tool; a real external-API tool with real egress is out of scope for this phase.
 */
@Component
class MockWeatherTool implements Tool {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> CONDITIONS = List.of("clear", "cloudy", "rainy", "windy", "snowy");

    private static final ToolDefinition DEFINITION = new ToolDefinition(
            "mock-weather",
            "1",
            "Looks up the current weather for a city. Mock data only, not a real forecast.",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"city":{"type":"string"}},"required":["city"],\
            "additionalProperties":false}""",
            """
            {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
            "properties":{"city":{"type":"string"},"temperatureCelsius":{"type":"number"},\
            "condition":{"type":"string"}},"required":["city","temperatureCelsius","condition"]}""",
            Set.of("external-api:mock"),
            false,
            Duration.ofSeconds(5));

    @Override
    public ToolDefinition definition() {
        return DEFINITION;
    }

    @Override
    public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
        String city;
        try {
            JsonNode arguments = JSON.readTree(argumentsJson);
            city = arguments.path("city").asString();
        } catch (RuntimeException e) {
            return ToolResult.failure("Could not read 'city' from arguments: " + e.getMessage());
        }
        if (city == null || city.isBlank()) {
            return ToolResult.failure("'city' must not be blank");
        }

        Random random = new Random(city.toLowerCase().hashCode());
        int temperatureCelsius = -10 + random.nextInt(45);
        String condition = CONDITIONS.get(random.nextInt(CONDITIONS.size()));

        String resultJson = JSON.writeValueAsString(JSON.createObjectNode()
                .put("city", city)
                .put("temperatureCelsius", temperatureCelsius)
                .put("condition", condition));
        return ToolResult.ok(resultJson);
    }
}
