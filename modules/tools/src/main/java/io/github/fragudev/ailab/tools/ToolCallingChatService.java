package io.github.fragudev.ailab.tools;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.shared.ConversationId;
import io.github.fragudev.ailab.tools.internal.PendingConfirmationRegistry;
import io.github.fragudev.ailab.tools.internal.ToolCallSniffer;
import io.github.fragudev.ailab.tools.internal.ToolsProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Drives a tool-calling-aware model turn — called by both {@code conversation.ConversationService}
 * (plain chat, {@link ToolCallOrigin#PLAIN_CHAT}) and {@code rag.RagPipeline}
 * ({@link ToolCallOrigin#RAG_CONTEXT}) in place of a direct {@code chatProvider.stream(...)} call.
 * Both real adapters (docs/architecture.md §8) report {@code supportsNativeToolCalling() == false},
 * so this always drives the structured-output fallback in practice — the "native" branch is a
 * documented no-op passthrough, kept for when a future adapter can produce {@code true}
 * (docs/adr/0009-tool-design-and-security-boundaries.md).
 *
 * <p><b>Confirmation gate (docs/threat-model.md T2).</b> A tool call is gated on
 * {@code POST /api/v1/tool-calls/{callId}:confirm} once the turn's context is "untrusted" — seeded
 * {@code true} for {@link ToolCallOrigin#RAG_CONTEXT}, and latched permanently {@code true} the
 * moment any tool with {@link ToolDefinition#introducesRetrievedContent()} is called, even from a
 * turn that started as {@link ToolCallOrigin#PLAIN_CHAT}: a plain-chat turn that calls
 * knowledge-base-search is correctly ungated for that first call (nothing untrusted yet), but every
 * call after it is gated, because the model's context now contains retrieved content.
 *
 * <p><b>MCP-client tools are gated unconditionally (docs/threat-model.md T9).</b> A tool whose
 * {@link ToolDefinition#alwaysRequiresConfirmation()} is {@code true} is confirmed every time,
 * regardless of the turn's origin or latching state — deliberately stricter than the rule above, since
 * the risk there isn't "the model's context contains untrusted content" but "this call sends
 * arguments to a third-party process this application doesn't control," which is true from its very
 * first invocation.
 */
@Service
public class ToolCallingChatService {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Matches the opening of this project's own tool-call envelope, tolerating the whitespace a
     * model may insert. Deliberately specific: it anchors on the {@code "tool_call"} key rather than
     * on any JSON object, so ordinary JSON in an answer is left alone. */
    private static final Pattern ENVELOPE_START = Pattern.compile("\\{\\s*\"tool_call\"\\s*:");

    private final ToolInvoker toolInvoker;
    private final PendingConfirmationRegistry confirmationRegistry;
    private final ToolsProperties properties;

    public ToolCallingChatService(
            ToolInvoker toolInvoker, PendingConfirmationRegistry confirmationRegistry, ToolsProperties properties) {
        this.toolInvoker = toolInvoker;
        this.confirmationRegistry = confirmationRegistry;
        this.properties = properties;
    }

    public Flux<ToolChatChunk> stream(
            ChatProvider provider,
            List<ChatMessage> history,
            List<ToolDefinition> availableTools,
            ToolCallOrigin origin,
            @Nullable ConversationId conversationId) {
        if (availableTools.isEmpty() || !properties.enabled()) {
            return provider.stream(new ChatRequest(history)).map(ToolCallingChatService::toPassthroughChunk);
        }
        LoopState state = new LoopState(new ArrayList<>(history), origin == ToolCallOrigin.RAG_CONTEXT, conversationId);
        return runRound(provider, availableTools, state);
    }

    private Flux<ToolChatChunk> runRound(ChatProvider provider, List<ToolDefinition> allTools, LoopState state) {
        boolean toolsAvailableThisRound = state.toolCallsMade < properties.maxCallsPerTurn();
        List<ChatMessage> requestMessages =
                toolsAvailableThisRound ? withToolSystemPrompt(state.history, allTools) : state.history;

        Flux<ChatChunk> raw = ToolCallSniffer.sniff(provider.stream(new ChatRequest(requestMessages)));
        return raw.concatMap(chunk -> {
            if (!chunk.last()) {
                return Flux.just(ToolChatChunk.delta(chunk.deltaContent()));
            }
            ChatResponse response = chunk.aggregate();
            state.responses.add(response);

            Optional<ParsedToolCall> parsed =
                    toolsAvailableThisRound ? tryParseToolCall(response.content()) : Optional.empty();
            if (parsed.isEmpty()) {
                // Strip before delivering (issue #62): with the tool budget spent this branch is
                // also the path an unparsed envelope takes, and raw protocol JSON must never be the
                // user's answer.
                return Flux.just(ToolChatChunk.last(
                        combine(state.responses, stripToolCallEnvelopes(response.content())), state.invocations));
            }
            return handleToolCall(parsed.get(), allTools, state)
                    .concatWith(Flux.defer(() -> runRound(provider, allTools, state)));
        });
    }

    private Flux<ToolChatChunk> handleToolCall(ParsedToolCall call, List<ToolDefinition> allTools, LoopState state) {
        UUID callId = UUID.randomUUID();
        state.toolCallsMade++;
        state.history.add(ChatMessage.assistant(toEnvelope(call)));

        Flux<ToolChatChunk> callEvent =
                Flux.just(ToolChatChunk.toolCall(new ToolCallRequest(callId, call.name(), call.argumentsJson())));

        Optional<ToolDefinition> definition = allTools.stream()
                .filter(candidate -> candidate.name().equals(call.name()))
                .findFirst();
        if (definition.isEmpty()) {
            // Fail closed (docs/threat-model.md T2/T9), not open: allTools — the list this turn was
            // actually offered and gated against — is the sole source of truth for whether a call may
            // reach the executor. A name absent here must never execute, even if ToolInvoker's global
            // ToolRegistry still knows it; that gap is exactly how a call could dodge confirmation.
            ToolCallResult unknown = new ToolCallResult(
                    callId,
                    call.name(),
                    call.argumentsJson(),
                    ToolCallOutcome.ERROR,
                    null,
                    "Unknown tool for this turn: '%s'".formatted(call.name()),
                    0);
            return callEvent.concatWith(recordResult(unknown, state));
        }

        boolean requiresConfirmation = state.untrusted || definition.get().alwaysRequiresConfirmation();
        if (definition.get().introducesRetrievedContent()) {
            state.untrusted = true;
        }

        Mono<ToolCallResult> resultMono;
        if (requiresConfirmation) {
            ToolCallConfirmationRequest pending = new ToolCallConfirmationRequest(
                    callId, call.name(), call.argumentsJson(), properties.confirmationTimeout());
            callEvent = callEvent.concatWith(Flux.just(ToolChatChunk.pending(pending)));
            resultMono = confirmationRegistry
                    .await(callId, properties.confirmationTimeout())
                    .flatMap(approved -> approved
                            ? toolInvoker.invokeForChat(
                                    callId, call.name(), call.argumentsJson(), executionContext(state))
                            : Mono.just(new ToolCallResult(
                                    callId,
                                    call.name(),
                                    call.argumentsJson(),
                                    ToolCallOutcome.DENIED,
                                    null,
                                    "Denied by user",
                                    0)))
                    .onErrorResume(e -> Mono.just(confirmationErrorResult(callId, call, e)));
        } else {
            resultMono = toolInvoker.invokeForChat(callId, call.name(), call.argumentsJson(), executionContext(state));
        }

        return callEvent.concatWith(resultMono.flatMapMany(result -> recordResult(result, state)));
    }

    private Flux<ToolChatChunk> recordResult(ToolCallResult result, LoopState state) {
        state.invocations.add(result);
        state.history.add(new ChatMessage(io.github.fragudev.ailab.aiprovider.ChatRole.TOOL, toResultPayload(result)));
        return Flux.just(ToolChatChunk.toolResult(result));
    }

    private ToolExecutionContext executionContext(LoopState state) {
        return new ToolExecutionContext(state.conversationId, state.untrusted);
    }

    private static ToolChatChunk toPassthroughChunk(ChatChunk chunk) {
        return chunk.last()
                ? ToolChatChunk.last(chunk.aggregate(), List.of())
                : ToolChatChunk.delta(chunk.deltaContent());
    }

    private static List<ChatMessage> withToolSystemPrompt(List<ChatMessage> history, List<ToolDefinition> tools) {
        StringBuilder prompt = new StringBuilder(
                "You may call at most one tool per turn if it would help answer the question. Available tools:\n");
        for (ToolDefinition tool : tools) {
            prompt.append("- ")
                    .append(tool.name())
                    .append(": ")
                    .append(tool.description())
                    .append(" Arguments schema: ")
                    .append(tool.inputSchemaJson())
                    .append('\n');
        }
        prompt.append("To call a tool, your ENTIRE response must be exactly one JSON object of the form "
                + "{\"tool_call\":{\"name\":\"<tool name>\",\"arguments\":{...}}} — nothing before or after it. "
                + "Never start a normal answer with '{'. If no tool is needed, answer normally in plain text.");
        List<ChatMessage> augmented = new ArrayList<>();
        augmented.add(ChatMessage.system(prompt.toString()));
        augmented.addAll(history);
        return augmented;
    }

    /** {@code confirmationRegistry.await} can fail two distinct ways: a real {@link
     * TimeoutException} (nobody confirmed in time) or, since post-roadmap review B4, an {@code
     * IllegalStateException} when {@code ai.tools.max-pending-confirmations} is already at capacity
     * — a system-busy rejection, not a timeout, and reported as such rather than folded into the
     * same message. */
    private ToolCallResult confirmationErrorResult(UUID callId, ParsedToolCall call, Throwable e) {
        if (e instanceof TimeoutException) {
            return new ToolCallResult(
                    callId,
                    call.name(),
                    call.argumentsJson(),
                    ToolCallOutcome.TIMEOUT,
                    null,
                    "Confirmation not received within " + properties.confirmationTimeout(),
                    0);
        }
        return new ToolCallResult(
                callId, call.name(), call.argumentsJson(), ToolCallOutcome.ERROR, null, e.getMessage(), 0);
    }

    private static String toEnvelope(ParsedToolCall call) {
        return "{\"tool_call\":{\"name\":\"%s\",\"arguments\":%s}}".formatted(call.name(), call.argumentsJson());
    }

    private static String toResultPayload(ToolCallResult result) {
        return switch (result.outcome()) {
            case OK -> result.resultJson();
            case DENIED -> "Tool call denied: " + result.message();
            case TIMEOUT -> "Tool call timed out: " + result.message();
            case ERROR -> "Tool call failed: " + result.message();
        };
    }

    /**
     * Locates a tool-call envelope anywhere in {@code content}, not only at its start.
     *
     * <p>The system prompt tells the model its entire response must be the envelope and "nothing
     * before or after it". Models do not reliably comply. Measured over a full 84-case live run
     * (post-roadmap review issue #62): 10 answers contained a raw envelope, and 4 of those had prose
     * in front of it — {@code readTree} on the whole string threw, no call was parsed, and the model's
     * search request was **silently never executed** while the raw JSON went to the user as the
     * answer. That is a functional failure, not a display bug.
     *
     * <p>Returns the envelope's exact substring so the caller can both parse it and remove it. The
     * scan is a brace-balanced walk that respects JSON string literals and escapes, so an argument
     * value containing {@code {} or "} cannot end the envelope early.
     */
    private static Optional<String> findEnvelope(String content) {
        Matcher matcher = ENVELOPE_START.matcher(content);
        if (!matcher.find()) {
            return Optional.empty();
        }
        int start = matcher.start();
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString && c == '{') {
                depth++;
            } else if (!inString && c == '}') {
                depth--;
                if (depth == 0) {
                    return Optional.of(content.substring(start, i + 1));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Removes every tool-call envelope from text destined for the user (post-roadmap review issue
     * #62). Needed because the loop stops parsing envelopes once {@code ai.tools.max-calls-per-turn}
     * is spent, while the model — primed by its own prior envelope-shaped turns in the history —
     * keeps emitting them; the raw protocol JSON then went out verbatim as the answer. 6 of the 10
     * leaks in that live run took this path.
     *
     * <p>Structural, matching only this project's own envelope shape — never a heuristic on prose,
     * so it cannot truncate a legitimate answer that merely discusses its sources.
     */
    static String stripToolCallEnvelopes(String content) {
        String remaining = content;
        Optional<String> envelope;
        while ((envelope = findEnvelope(remaining)).isPresent()) {
            remaining = remaining.replace(envelope.get(), "");
        }
        return remaining.strip();
    }

    private static Optional<ParsedToolCall> tryParseToolCall(String content) {
        Optional<String> envelope = findEnvelope(content);
        if (envelope.isEmpty()) {
            return Optional.empty();
        }
        try {
            JsonNode root = JSON.readTree(envelope.get());
            JsonNode call = root.path("tool_call");
            if (!call.isObject()) {
                return Optional.empty();
            }
            JsonNode nameNode = call.path("name");
            JsonNode argumentsNode = call.path("arguments");
            if (!nameNode.isString() || argumentsNode.isMissingNode()) {
                return Optional.empty();
            }
            return Optional.of(new ParsedToolCall(nameNode.asString(), JSON.writeValueAsString(argumentsNode)));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static ChatResponse combine(List<ChatResponse> responses, String finalContent) {
        int promptTokens =
                responses.stream().mapToInt(r -> r.usage().promptTokens()).sum();
        int completionTokens =
                responses.stream().mapToInt(r -> r.usage().completionTokens()).sum();
        Duration totalLatency = responses.stream().map(ChatResponse::latency).reduce(Duration.ZERO, Duration::plus);
        BigDecimal totalCost =
                responses.stream().map(ChatResponse::estimatedCostUsd).reduce(BigDecimal.ZERO, BigDecimal::add);
        String model = responses.get(responses.size() - 1).model();
        return new ChatResponse(
                finalContent, model, new TokenUsage(promptTokens, completionTokens), totalLatency, totalCost);
    }

    private record ParsedToolCall(String name, String argumentsJson) {}

    private static final class LoopState {
        final List<ChatMessage> history;
        final List<ChatResponse> responses = new ArrayList<>();
        final List<ToolCallResult> invocations = new ArrayList<>();
        final @Nullable ConversationId conversationId;
        boolean untrusted;
        int toolCallsMade;

        LoopState(List<ChatMessage> history, boolean untrusted, @Nullable ConversationId conversationId) {
            this.history = history;
            this.untrusted = untrusted;
            this.conversationId = conversationId;
        }
    }
}
