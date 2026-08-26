package io.github.fragudev.ailab.tools;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.ProviderCapabilities;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.tools.internal.PendingConfirmationRegistry;
import io.github.fragudev.ailab.tools.internal.SchemaValidator;
import io.github.fragudev.ailab.tools.internal.ScopeAuthorizer;
import io.github.fragudev.ailab.tools.internal.ToolsProperties;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/**
 * The tool-calling loop, exercised with a hand-written scripted {@link ChatProvider} and hand-
 * written {@link Tool} fakes — no mocking framework anywhere in this codebase (see
 * {@code LmStudioChatProviderTest} for the same style). {@link ToolInvoker} is constructed for
 * real (schema validation, scope authorization and timeout are genuinely exercised) but with a
 * {@code null} repository/metrics: {@code ToolInvoker#invokeForChat} — the only entry point this
 * class calls — never touches either, only {@code invokeOrThrow}/{@code recordForMessage} do, so
 * passing {@code null} here is safe and self-verifying (a future change routing persistence through
 * {@code invokeForChat} would NPE this test immediately rather than silently pass).
 */
class ToolCallingChatServiceTest {

    private static final ProviderCapabilities FALLBACK_CAPABILITIES = new ProviderCapabilities(false, false, 8192);

    private final ToolsProperties properties =
            new ToolsProperties(true, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, 10);

    @Test
    void emptyToolsListIsAnUnmodifiedPassthrough() {
        ScriptedChatProvider provider = new ScriptedChatProvider(FALLBACK_CAPABILITIES, "Hello there.");
        ToolCallingChatService service = newService(properties, List.of());

        List<ToolChatChunk> chunks = service.stream(provider, history("hi"), List.of(), ToolCallOrigin.PLAIN_CHAT, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(provider.requestsSeen()).hasSize(1);
        assertThat(provider.requestsSeen().get(0).messages()).hasSize(1); // no system prompt injected
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.get(chunks.size() - 1).last()).isTrue();
        assertThat(chunks.get(chunks.size() - 1).aggregate().content()).isEqualTo("Hello there.");
    }

    /** Issue #62 (post-roadmap review): the system prompt demands the envelope be the entire response
     * with "nothing before or after it", and models do not comply. Measured over a full 84-case live
     * run, 4 answers put prose in front of the envelope — parsing the whole content as JSON threw, so
     * the tool call was <b>never executed</b> and the raw envelope was delivered as the answer. The
     * call must now be found and run, and the envelope must not reach the user. */
    @Test
    void anEnvelopePrecededByProseStillExecutesTheToolAndNeverReachesTheUser() {
        AtomicInteger executions = new AtomicInteger();
        Tool searchTool = fakeTool("weather-like", Set.of(), false, executions);
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES,
                "Let me look that up for you.\n\n{\"tool_call\":{\"name\":\"weather-like\",\"arguments\":{}}}",
                "It is sunny.");
        ToolCallingChatService service = newService(properties, List.of(searchTool));

        List<ToolChatChunk> chunks = service.stream(
                        provider,
                        history("weather?"),
                        List.of(searchTool.definition()),
                        ToolCallOrigin.PLAIN_CHAT,
                        null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(executions).hasValue(1);
        ToolChatChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.aggregate().content()).isEqualTo("It is sunny.").doesNotContain("tool_call");
    }

    /** Issue #62, the other route to the same leak: once {@code max-calls-per-turn} (3 here) is spent
     * the loop stops parsing envelopes, but the model — primed by its own envelope-shaped turns in the
     * history — keeps emitting them. 6 of the 10 leaks in the live run arrived this way. The budget
     * must still be enforced, and the raw protocol JSON must still not reach the user. */
    @Test
    void anEnvelopeArrivingAfterTheToolBudgetIsSpentIsStrippedNotDelivered() {
        AtomicInteger executions = new AtomicInteger();
        Tool tool = fakeTool("weather-like", Set.of(), false, executions);
        String envelope = "{\"tool_call\":{\"name\":\"weather-like\",\"arguments\":{}}}";
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES, envelope, envelope, envelope, envelope + "\n\nI could not look that up.");
        ToolCallingChatService service = newService(properties, List.of(tool));

        List<ToolChatChunk> chunks = service.stream(
                        provider, history("weather?"), List.of(tool.definition()), ToolCallOrigin.PLAIN_CHAT, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(executions).hasValue(3);
        ToolChatChunk last = chunks.get(chunks.size() - 1);
        assertThat(last.aggregate().content())
                .isEqualTo("I could not look that up.")
                .doesNotContain("tool_call");
    }

    @Test
    void scopeDeniedShortCircuitsWithoutExecutingTheTool() {
        AtomicInteger executions = new AtomicInteger();
        Tool deniedTool = fakeTool("weather-like", Set.of("external-api:mock"), false, executions);
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES,
                "{\"tool_call\":{\"name\":\"weather-like\",\"arguments\":{}}}",
                "Sorry, I couldn't check that.");
        ToolCallingChatService service = newService(properties, List.of(deniedTool));

        List<ToolChatChunk> chunks = service.stream(
                        provider,
                        history("weather?"),
                        List.of(deniedTool.definition()),
                        ToolCallOrigin.PLAIN_CHAT,
                        null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(executions).hasValue(0);
        ToolChatChunk resultChunk =
                chunks.stream().filter(c -> c.toolResult() != null).findFirst().orElseThrow();
        assertThat(resultChunk.toolResult().outcome()).isEqualTo(ToolCallOutcome.DENIED);
    }

    /** Issue #22 (post-roadmap review S2): the confirmation gate and the executor must resolve a
     * tool call from the same set. {@code phantom-tool} is registered globally — as an MCP-discovered
     * tool would be after Phase 7 made {@link ToolRegistry} mutable — but never appears in
     * {@code allTools}, the list this turn was actually offered and gated against. Before this fix,
     * {@code definition.isEmpty()} made {@code requiresConfirmation} false and the call went straight
     * to {@link ToolInvoker}, fully bypassing confirmation; it must now fail closed instead. */
    @Test
    void unknownToolForThisTurnFailsClosedInsteadOfExecuting() {
        AtomicInteger phantomExecutions = new AtomicInteger();
        Tool phantomTool = fakeTool("phantom-tool", Set.of(), false, phantomExecutions);
        Tool decoyTool = fakeTool("decoy-tool", Set.of(), false, new AtomicInteger());
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES, "{\"tool_call\":{\"name\":\"phantom-tool\",\"arguments\":{}}}", "Gave up.");
        ToolInvoker invoker = new ToolInvoker(
                new ToolRegistry(List.of(phantomTool, decoyTool)),
                new SchemaValidator(),
                new ScopeAuthorizer(properties),
                null,
                null);
        ToolCallingChatService service =
                new ToolCallingChatService(invoker, new PendingConfirmationRegistry(properties), properties);

        List<ToolChatChunk> chunks = service.stream(
                        provider, history("go"), List.of(decoyTool.definition()), ToolCallOrigin.PLAIN_CHAT, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(phantomExecutions).hasValue(0);
        ToolChatChunk resultChunk =
                chunks.stream().filter(c -> c.toolResult() != null).findFirst().orElseThrow();
        assertThat(resultChunk.toolResult().outcome()).isEqualTo(ToolCallOutcome.ERROR);
        assertThat(resultChunk.toolResult().toolName()).isEqualTo("phantom-tool");
        assertThat(chunks.get(chunks.size() - 1).aggregate().content()).isEqualTo("Gave up.");
    }

    @Test
    void timeoutIsReportedWithoutHangingTheStream() {
        Tool slowTool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(
                        "slow-tool", "1", "never finishes", "{}", "{}", Set.of(), false, false, Duration.ofMillis(50));
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return ToolResult.ok("{}");
            }
        };
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES, "{\"tool_call\":{\"name\":\"slow-tool\",\"arguments\":{}}}", "Gave up waiting.");
        ToolCallingChatService service = newService(properties, List.of(slowTool));

        List<ToolChatChunk> chunks = service.stream(
                        provider, history("go"), List.of(slowTool.definition()), ToolCallOrigin.PLAIN_CHAT, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        ToolChatChunk resultChunk =
                chunks.stream().filter(c -> c.toolResult() != null).findFirst().orElseThrow();
        assertThat(resultChunk.toolResult().outcome()).isEqualTo(ToolCallOutcome.TIMEOUT);
        assertThat(chunks.get(chunks.size() - 1).last()).isTrue();
    }

    @Test
    void invalidArgumentsAreFedBackAndTheModelRetries() {
        AtomicInteger executions = new AtomicInteger();
        ToolDefinition definition = new ToolDefinition(
                "needs-value", "1", "requires a value", """
                {"$schema":"https://json-schema.org/draft/2020-12/schema","type":"object",\
                "properties":{"value":{"type":"string"}},"required":["value"]}""", "{}", Set.of(), false, false, Duration.ofSeconds(5));
        Tool tool = new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
                executions.incrementAndGet();
                return ToolResult.ok("{\"ok\":true}");
            }
        };
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES,
                "{\"tool_call\":{\"name\":\"needs-value\",\"arguments\":{}}}",
                "{\"tool_call\":{\"name\":\"needs-value\",\"arguments\":{\"value\":\"ok\"}}}",
                "Done.");
        ToolCallingChatService service = newService(properties, List.of(tool));

        List<ToolChatChunk> chunks = service.stream(
                        provider, history("go"), List.of(definition), ToolCallOrigin.PLAIN_CHAT, null)
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(provider.requestsSeen()).hasSize(3);
        assertThat(executions).hasValue(1);
        List<ToolChatChunk> resultChunks =
                chunks.stream().filter(c -> c.toolResult() != null).toList();
        assertThat(resultChunks).hasSize(2);
        assertThat(resultChunks.get(0).toolResult().outcome()).isEqualTo(ToolCallOutcome.ERROR);
        assertThat(resultChunks.get(1).toolResult().outcome()).isEqualTo(ToolCallOutcome.OK);
        assertThat(chunks.get(chunks.size() - 1).aggregate().content()).isEqualTo("Done.");
    }

    @Test
    void confirmationGateLatchesAfterAToolIntroducingRetrievedContentExecutes() throws InterruptedException {
        AtomicInteger kbExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        Tool kbTool = fakeTool("kb-search", Set.of(), true, kbExecutions);
        Tool secondTool = fakeTool("second-tool", Set.of(), false, secondExecutions);
        PendingConfirmationRegistry registry = new PendingConfirmationRegistry(properties);
        ScriptedChatProvider provider = new ScriptedChatProvider(
                FALLBACK_CAPABILITIES,
                "{\"tool_call\":{\"name\":\"kb-search\",\"arguments\":{}}}",
                "{\"tool_call\":{\"name\":\"second-tool\",\"arguments\":{}}}",
                "Final answer.");
        ToolInvoker invoker = new ToolInvoker(
                new ToolRegistry(List.of(kbTool, secondTool)),
                new SchemaValidator(),
                new ScopeAuthorizer(properties),
                null,
                null);
        ToolCallingChatService service = new ToolCallingChatService(invoker, registry, properties);

        // ToolInvoker.invokeForChat genuinely runs each tool on Schedulers.boundedElastic() now (a
        // real fix, see ToolInvoker's javadoc) — .subscribe() returns before that work necessarily
        // finishes, so synchronization here is by explicit latches on specific emitted chunks, not
        // by asserting immediately after subscribing.
        List<ToolChatChunk> collected = new CopyOnWriteArrayList<>();
        CountDownLatch pendingLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(1);
        service.stream(
                        provider,
                        history("search then act"),
                        List.of(kbTool.definition(), secondTool.definition()),
                        ToolCallOrigin.PLAIN_CHAT,
                        null)
                .doOnNext(chunk -> {
                    collected.add(chunk);
                    if (chunk.pendingConfirmation() != null) {
                        pendingLatch.countDown();
                    }
                    if (chunk.last()) {
                        doneLatch.countDown();
                    }
                })
                .subscribe();

        assertThat(pendingLatch.await(5, TimeUnit.SECONDS)).isTrue();

        // kb-search ran ungated (nothing untrusted yet); second-tool's call is now pending confirmation.
        assertThat(kbExecutions).hasValue(1);
        assertThat(secondExecutions).hasValue(0);
        ToolChatChunk pendingChunk = collected.stream()
                .filter(c -> c.pendingConfirmation() != null)
                .findFirst()
                .orElseThrow();
        UUID callId = pendingChunk.pendingConfirmation().callId();
        assertThat(pendingChunk.pendingConfirmation().toolName()).isEqualTo("second-tool");

        // registry.await registers on subscription, not on call (post-roadmap review B4), and that
        // subscription only happens once callEvent (the pending chunk emitted above) has finished —
        // a genuine, narrow async gap between "the client sees the pending confirmation" and "the
        // registry can resolve it" that didn't exist under the old eager-registration behaviour.
        // Negligible in production (a real confirm click is milliseconds-to-seconds away over the
        // network); resolveEventually spins briefly rather than asserting the very first attempt.
        assertThat(resolveEventually(registry, callId)).isTrue();

        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(secondExecutions).hasValue(1);
        assertThat(collected.get(collected.size() - 1).last()).isTrue();
        assertThat(collected.get(collected.size() - 1).aggregate().content()).isEqualTo("Final answer.");
    }

    private static boolean resolveEventually(PendingConfirmationRegistry registry, UUID callId)
            throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (registry.resolve(callId, true)) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }

    private static ToolCallingChatService newService(ToolsProperties properties, List<Tool> tools) {
        ToolInvoker invoker = new ToolInvoker(
                new ToolRegistry(tools), new SchemaValidator(), new ScopeAuthorizer(properties), null, null);
        return new ToolCallingChatService(invoker, new PendingConfirmationRegistry(properties), properties);
    }

    private static Tool fakeTool(
            String name, Set<String> requiredScopes, boolean introducesRetrievedContent, AtomicInteger executions) {
        ToolDefinition definition = new ToolDefinition(
                name,
                "1",
                "test tool",
                "{}",
                "{}",
                requiredScopes,
                introducesRetrievedContent,
                false,
                Duration.ofSeconds(5));
        return new Tool() {
            @Override
            public ToolDefinition definition() {
                return definition;
            }

            @Override
            public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
                executions.incrementAndGet();
                return ToolResult.ok("{\"ok\":true}");
            }
        };
    }

    private static List<ChatMessage> history(String userContent) {
        return List.of(ChatMessage.user(userContent));
    }

    /** Returns one scripted response per call, in order, as a single delta + a terminal chunk —
     * good enough to drive {@link ToolCallingChatService}'s sniffer/parsing, not a real streaming
     * simulation. */
    private static final class ScriptedChatProvider implements ChatProvider {

        private final ProviderCapabilities capabilities;
        private final Deque<String> responses;
        private final List<ChatRequest> requestsSeen = new ArrayList<>();

        ScriptedChatProvider(ProviderCapabilities capabilities, String... responses) {
            this.capabilities = capabilities;
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        List<ChatRequest> requestsSeen() {
            return requestsSeen;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            throw new UnsupportedOperationException("not exercised by this test");
        }

        @Override
        public Flux<ChatChunk> stream(ChatRequest request) {
            requestsSeen.add(request);
            String content = responses.poll();
            if (content == null) {
                throw new IllegalStateException("No more scripted responses for request: " + request);
            }
            ChatResponse aggregate =
                    new ChatResponse(content, "test-model", new TokenUsage(1, 1), Duration.ZERO, BigDecimal.ZERO);
            return Flux.just(ChatChunk.delta(content), ChatChunk.last(aggregate));
        }

        @Override
        public ProviderCapabilities capabilities() {
            return capabilities;
        }
    }
}
