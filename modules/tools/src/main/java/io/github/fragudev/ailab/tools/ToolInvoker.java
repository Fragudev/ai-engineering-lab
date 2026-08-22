package io.github.fragudev.ailab.tools;

import io.github.fragudev.ailab.shared.MessageId;
import io.github.fragudev.ailab.shared.ToolAuthorizationException;
import io.github.fragudev.ailab.shared.ToolInvocationId;
import io.github.fragudev.ailab.shared.ToolTimeoutException;
import io.github.fragudev.ailab.tools.internal.SchemaValidator;
import io.github.fragudev.ailab.tools.internal.ScopeAuthorizer;
import io.github.fragudev.ailab.tools.internal.ToolInvocation;
import io.github.fragudev.ailab.tools.internal.ToolInvocationRepository;
import io.github.fragudev.ailab.tools.internal.ToolMetrics;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * The one place schema validation, scope authorization, timeout-bounded execution, metrics and
 * persistence happen for a tool call — shared by the direct {@code POST /api/v1/tools/{name}:invoke}
 * path ({@link #invokeOrThrow}, which blocks and throws typed exceptions the API edge maps to
 * 400/403/404/504 — safe here, since a plain Spring MVC controller method runs on a servlet
 * container thread, not inside a Reactor pipeline) and the chat tool-calling loop
 * ({@link #invokeForChat}, which returns a {@code Mono} and never throws for an expected failure
 * mode — {@link ToolCallingChatService} needs a result to keep streaming, not an exception, and
 * genuinely cannot block: it runs inside the same reactive chain the model's own streamed response
 * flows through, where Reactor's own blocking-call guard on the {@code parallel} scheduler rejects
 * a synchronous {@code block()} outright — a real failure this design hit and fixed, not a
 * hypothetical one). A chat-triggered call is persisted separately via {@link #recordForMessage},
 * once its owning message exists (mirrors how {@code Citation} rows are linked after the fact in
 * {@code app.ConversationController}) — {@link #invokeForChat} itself never touches the database.
 */
@Service
public class ToolInvoker {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ToolRegistry registry;
    private final SchemaValidator schemaValidator;
    private final ScopeAuthorizer scopeAuthorizer;
    private final ToolMetrics metrics;
    private final ToolInvocationRepository repository;

    public ToolInvoker(
            ToolRegistry registry,
            SchemaValidator schemaValidator,
            ScopeAuthorizer scopeAuthorizer,
            ToolMetrics metrics,
            ToolInvocationRepository repository) {
        this.registry = registry;
        this.schemaValidator = schemaValidator;
        this.scopeAuthorizer = scopeAuthorizer;
        this.metrics = metrics;
        this.repository = repository;
    }

    public ToolResult invokeOrThrow(String toolName, String argumentsJson, ToolExecutionContext context) {
        Tool tool = registry.find(toolName)
                .orElseThrow(() -> new NoSuchElementException("Unknown tool: '%s'".formatted(toolName)));
        ToolDefinition definition = tool.definition();

        if (!scopeAuthorizer.isAuthorized(definition)) {
            Set<String> missing = scopeAuthorizer.missingScopes(definition);
            persistAndMeter(null, toolName, argumentsJson, null, ToolCallOutcome.DENIED, Duration.ZERO);
            throw new ToolAuthorizationException(toolName, missing);
        }

        List<String> violations = schemaValidator.validate(definition.inputSchemaJson(), argumentsJson);
        if (!violations.isEmpty()) {
            persistAndMeter(null, toolName, argumentsJson, null, ToolCallOutcome.ERROR, Duration.ZERO);
            throw new IllegalArgumentException("Invalid arguments for tool '%s': %s".formatted(toolName, violations));
        }

        Instant start = Instant.now();
        // .block() is safe only here: a plain Spring MVC controller method runs on a servlet
        // container thread, never inside a Reactor pipeline (see invokeForChat's javadoc for the
        // real failure that ruled this out there).
        Execution execution =
                execute(tool, context, argumentsJson, definition.timeout()).block();
        Duration elapsed = Duration.between(start, Instant.now());

        if (execution.timedOut()) {
            persistAndMeter(null, toolName, argumentsJson, null, ToolCallOutcome.TIMEOUT, elapsed);
            throw new ToolTimeoutException(toolName, definition.timeout());
        }

        ToolResult result = execution.result();
        ToolCallOutcome outcome = result.success() ? ToolCallOutcome.OK : ToolCallOutcome.ERROR;
        persistAndMeter(null, toolName, argumentsJson, result.resultJson(), outcome, elapsed);
        return result;
    }

    public Mono<ToolCallResult> invokeForChat(
            UUID callId, String toolName, String argumentsJson, ToolExecutionContext context) {
        Optional<Tool> maybeTool = registry.find(toolName);
        if (maybeTool.isEmpty()) {
            return Mono.just(new ToolCallResult(
                    callId,
                    toolName,
                    argumentsJson,
                    ToolCallOutcome.ERROR,
                    null,
                    "Unknown tool: '%s'".formatted(toolName),
                    0));
        }
        Tool tool = maybeTool.get();
        ToolDefinition definition = tool.definition();

        if (!scopeAuthorizer.isAuthorized(definition)) {
            Set<String> missing = scopeAuthorizer.missingScopes(definition);
            return Mono.just(new ToolCallResult(
                    callId, toolName, argumentsJson, ToolCallOutcome.DENIED, null, "Missing scope(s): " + missing, 0));
        }

        List<String> violations = schemaValidator.validate(definition.inputSchemaJson(), argumentsJson);
        if (!violations.isEmpty()) {
            return Mono.just(new ToolCallResult(
                    callId,
                    toolName,
                    argumentsJson,
                    ToolCallOutcome.ERROR,
                    null,
                    "Invalid arguments: " + violations,
                    0));
        }

        Instant start = Instant.now();
        return execute(tool, context, argumentsJson, definition.timeout()).map(execution -> {
            long durationMs = Duration.between(start, Instant.now()).toMillis();
            if (execution.timedOut()) {
                return new ToolCallResult(
                        callId,
                        toolName,
                        argumentsJson,
                        ToolCallOutcome.TIMEOUT,
                        null,
                        "Timed out after " + definition.timeout(),
                        durationMs);
            }
            ToolResult result = execution.result();
            ToolCallOutcome outcome = result.success() ? ToolCallOutcome.OK : ToolCallOutcome.ERROR;
            return new ToolCallResult(
                    callId, toolName, argumentsJson, outcome, result.resultJson(), result.errorMessage(), durationMs);
        });
    }

    /** Persists a chat-triggered call once its owning message exists — includes calls that were
     * denied by the confirmation gate or timed out waiting for confirmation, which never reach
     * {@link #invokeForChat} at all (no {@code Tool.execute} was ever called for those). */
    public void recordForMessage(MessageId messageId, ToolCallResult result) {
        persistAndMeter(
                messageId,
                result.toolName(),
                result.argumentsJson(),
                result.resultJson(),
                result.outcome(),
                Duration.ofMillis(result.durationMs()));
    }

    private void persistAndMeter(
            @Nullable MessageId messageId,
            String toolName,
            String argumentsJson,
            @Nullable String resultJson,
            ToolCallOutcome outcome,
            Duration duration) {
        repository.save(new ToolInvocation(
                ToolInvocationId.generate(),
                messageId,
                toolName,
                toMap(argumentsJson),
                resultJson == null ? null : toMap(resultJson),
                outcome,
                duration.toMillis()));
        metrics.record(toolName, outcome, duration);
    }

    private static Map<String, Object> toMap(String json) {
        try {
            return JSON.readValue(json, MAP_TYPE);
        } catch (RuntimeException e) {
            return Map.of("raw", json);
        }
    }

    private record Execution(@Nullable ToolResult result, boolean timedOut) {}

    /** Never blocks — returns a {@code Mono} that {@link #invokeForChat} composes further and
     * {@link #invokeOrThrow} blocks on at its own (safe) boundary. */
    private static Mono<Execution> execute(
            Tool tool, ToolExecutionContext context, String argumentsJson, Duration timeout) {
        return Mono.fromCallable(() -> tool.execute(context, argumentsJson))
                .subscribeOn(Schedulers.boundedElastic())
                .timeout(timeout)
                .map(result -> new Execution(result, false))
                .onErrorResume(e -> {
                    Throwable root = Exceptions.unwrap(e);
                    if (root instanceof TimeoutException) {
                        return Mono.just(new Execution(null, true));
                    }
                    String message = root.getMessage() != null
                            ? root.getMessage()
                            : root.getClass().getSimpleName();
                    return Mono.just(new Execution(ToolResult.failure(message), false));
                });
    }
}
