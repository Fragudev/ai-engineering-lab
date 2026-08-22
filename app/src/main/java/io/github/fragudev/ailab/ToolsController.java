package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolInvoker;
import io.github.fragudev.ailab.tools.ToolRegistry;
import io.github.fragudev.ailab.tools.ToolResult;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/tools")
class ToolsController {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final ToolRegistry toolRegistry;
    private final ToolInvoker toolInvoker;

    ToolsController(ToolRegistry toolRegistry, ToolInvoker toolInvoker) {
        this.toolRegistry = toolRegistry;
        this.toolInvoker = toolInvoker;
    }

    @GetMapping
    List<ToolDefinitionResponse> list() {
        return toolRegistry.definitions().stream()
                .map(ToolDefinitionResponse::from)
                .toList();
    }

    @PostMapping("/{name}:invoke")
    ToolInvokeResponse invoke(@PathVariable String name, @RequestBody(required = false) ToolInvokeRequest request) {
        Map<String, Object> arguments = request == null || request.arguments() == null ? Map.of() : request.arguments();
        String argumentsJson = JSON.writeValueAsString(arguments);
        ToolResult result = toolInvoker.invokeOrThrow(name, argumentsJson, ToolExecutionContext.direct());
        return ToolInvokeResponse.from(result);
    }
}
