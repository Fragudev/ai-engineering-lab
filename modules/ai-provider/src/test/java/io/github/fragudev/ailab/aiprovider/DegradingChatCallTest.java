package io.github.fragudev.ailab.aiprovider;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Pure-function coverage for the call-the-model-and-degrade shape shared by five call sites
 * (post-roadmap review issue #36) — a hand-written fake {@link ChatProvider}, no mocking framework
 * in this codebase (AGENTS.md). */
class DegradingChatCallTest {

    private static final ChatRequest REQUEST = new ChatRequest(List.of(ChatMessage.user("hello")));

    @Test
    void returnsTheParsedResultAndTheRealCostOnSuccess() {
        ChatProvider provider = respondingWith("42", new BigDecimal("0.01"));

        DegradingChatCall.Outcome<Integer> outcome =
                DegradingChatCall.call(provider, REQUEST, Integer::parseInt, -1, e -> fail(), content -> fail());

        assertThat(outcome.value()).isEqualTo(42);
        assertThat(outcome.costUsd()).isEqualByComparingTo("0.01");
    }

    @Test
    void fallsBackWithTheRealCostWhenTheResponseIsUnparsable() {
        ChatProvider provider = respondingWith("not a number", new BigDecimal("0.01"));
        AtomicReference<String> seenContent = new AtomicReference<>();

        DegradingChatCall.Outcome<Integer> outcome = DegradingChatCall.call(
                provider,
                REQUEST,
                content -> content.chars().allMatch(Character::isDigit) ? Integer.parseInt(content) : null,
                -1,
                e -> fail(),
                seenContent::set);

        assertThat(outcome.value()).isEqualTo(-1);
        // A call that spent real tokens on an unusable response still cost real money (the helper's
        // own javadoc) — the fallback value is used, but the cost is not silently zeroed.
        assertThat(outcome.costUsd()).isEqualByComparingTo("0.01");
        assertThat(seenContent.get()).isEqualTo("not a number");
    }

    @Test
    void fallsBackWithZeroCostWhenTheProviderCallItselfFails() {
        ChatProvider provider = throwing(new RuntimeException("boom"));
        AtomicReference<RuntimeException> seenException = new AtomicReference<>();

        DegradingChatCall.Outcome<Integer> outcome =
                DegradingChatCall.call(provider, REQUEST, Integer::parseInt, -1, seenException::set, content -> fail());

        assertThat(outcome.value()).isEqualTo(-1);
        assertThat(outcome.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(seenException.get()).hasMessage("boom");
    }

    @Test
    void aProviderTimeoutIsTreatedAsAProviderFailureNotLeftToPropagate() {
        ChatProvider provider = throwing(new ProviderTimeoutException("test-provider", Duration.ofSeconds(5)));
        AtomicReference<RuntimeException> seenException = new AtomicReference<>();

        DegradingChatCall.Outcome<Integer> outcome =
                DegradingChatCall.call(provider, REQUEST, Integer::parseInt, -1, seenException::set, content -> fail());

        assertThat(outcome.value()).isEqualTo(-1);
        assertThat(outcome.costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(seenException.get()).isInstanceOf(ProviderTimeoutException.class);
    }

    private static void fail() {
        throw new AssertionError("this callback should not have been invoked");
    }

    private static ChatProvider respondingWith(String content, BigDecimal costUsd) {
        return new FakeChatProvider(new ChatResponse(content, "test-model", TokenUsage.zero(), Duration.ZERO, costUsd));
    }

    private static ChatProvider throwing(RuntimeException exception) {
        return new FakeChatProvider(exception);
    }

    /** A minimal hand-written fake — only {@code complete} is exercised by this test; the other two
     * interface methods throw rather than silently return a wrong value, matching this codebase's
     * established fake-repository style. */
    private static final class FakeChatProvider implements ChatProvider {
        private final ChatResponse response;
        private final RuntimeException exception;

        FakeChatProvider(ChatResponse response) {
            this.response = response;
            this.exception = null;
        }

        FakeChatProvider(RuntimeException exception) {
            this.response = null;
            this.exception = exception;
        }

        @Override
        public ChatResponse complete(ChatRequest request) {
            if (exception != null) {
                throw exception;
            }
            return response;
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
}
