package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class PendingConfirmationRegistryTest {

    private final PendingConfirmationRegistry registry = new PendingConfirmationRegistry();

    @Test
    void resolveBeforeTimeoutCompletesAwaitWithTheApprovedValue() throws Exception {
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofSeconds(5)).toFuture();

        boolean resolved = registry.resolve(callId, true);

        assertThat(resolved).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void resolveWithRejectionCompletesAwaitWithFalse() throws Exception {
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofSeconds(5)).toFuture();

        registry.resolve(callId, false);

        assertThat(future.get(2, TimeUnit.SECONDS)).isFalse();
    }

    @Test
    void unresolvedCallTimesOutWithAnErrorNotAFalseValue() {
        // Deliberately an error, not a false fallback value: ToolCallingChatService needs to tell
        // "explicitly rejected" (DENIED) apart from "nobody answered in time" (TIMEOUT) — a real
        // live run caught this exact conflation when both cases shared one false signal.
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofMillis(50)).toFuture();

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void resolvingAnUnknownCallIdReturnsFalse() {
        assertThat(registry.resolve(UUID.randomUUID(), true)).isFalse();
    }

    @Test
    void resolvingTwiceOnlySucceedsOnce() {
        UUID callId = UUID.randomUUID();
        registry.await(callId, Duration.ofSeconds(5)).subscribe();

        assertThat(registry.resolve(callId, true)).isTrue();
        assertThat(registry.resolve(callId, true)).isFalse();
    }
}
