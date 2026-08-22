package io.github.fragudev.ailab.workflow.internal;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.ProviderCapabilities;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/** A hand-written fake {@link ChatProvider} — no mocking framework in this codebase (AGENTS.md,
 * matching {@code LmStudioChatProviderTest}'s own style). Only {@link #complete} is exercised by
 * {@link SubQueryPlanner}/{@link SourceExtractor}/{@link AnswerSynthesiser}. */
class FakeChatProvider implements ChatProvider {

    private final Deque<String> responses;
    private final @Nullable RuntimeException failure;

    FakeChatProvider(String... responses) {
        this.responses = new ArrayDeque<>(List.of(responses));
        this.failure = null;
    }

    private FakeChatProvider(RuntimeException failure) {
        this.responses = new ArrayDeque<>();
        this.failure = failure;
    }

    static FakeChatProvider failingWith(RuntimeException failure) {
        return new FakeChatProvider(failure);
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        if (failure != null) {
            throw failure;
        }
        String response = responses.isEmpty() ? "" : responses.poll();
        return new ChatResponse(response, "fake-model", new TokenUsage(1, 1), Duration.ZERO, BigDecimal.ZERO);
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        throw new UnsupportedOperationException("not exercised by this test");
    }

    @Override
    public ProviderCapabilities capabilities() {
        throw new UnsupportedOperationException("not exercised by this test");
    }
}
