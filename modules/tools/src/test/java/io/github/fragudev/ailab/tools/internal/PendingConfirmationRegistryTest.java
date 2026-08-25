package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

class PendingConfirmationRegistryTest {

    private final ToolsProperties properties =
            new ToolsProperties(true, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, 10);
    private final PendingConfirmationRegistry registry = new PendingConfirmationRegistry(properties);

    @Test
    void resolveBeforeTimeoutCompletesAwaitWithTheApprovedValue() throws Exception {
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofSeconds(5)).toFuture();

        boolean resolved = registry.resolve(callId, true);

        assertThat(resolved).isTrue();
        assertThat(future.get(2, TimeUnit.SECONDS)).isTrue();
        // The entry must not outlive the completed Mono (post-roadmap review B4) — a second resolve
        // on the same callId finding nothing is the direct proof it was removed.
        assertThat(registry.resolve(callId, true)).isFalse();
    }

    @Test
    void resolveWithRejectionCompletesAwaitWithFalse() throws Exception {
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofSeconds(5)).toFuture();

        registry.resolve(callId, false);

        assertThat(future.get(2, TimeUnit.SECONDS)).isFalse();
        assertThat(registry.resolve(callId, true)).isFalse();
    }

    @Test
    void unresolvedCallTimesOutWithAnErrorNotAFalseValue() throws Exception {
        // Deliberately an error, not a false fallback value: ToolCallingChatService needs to tell
        // "explicitly rejected" (DENIED) apart from "nobody answered in time" (TIMEOUT) — a real
        // live run caught this exact conflation when both cases shared one false signal.
        UUID callId = UUID.randomUUID();
        CompletableFuture<Boolean> future =
                registry.await(callId, Duration.ofMillis(50)).toFuture();

        assertThatThrownBy(() -> future.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);

        // The timeout branch's doFinally must clean up too, not just the resolved/denied paths.
        // Unlike those paths — where resolve() emits on this very thread, so the whole chain
        // including doFinally has run by the time it returns — the timeout fires on a scheduler
        // thread and doFinally runs *after* the error reaches the subscriber above. Asserting
        // cleanup immediately therefore raced the runtime and failed intermittently in CI; waiting
        // for the map to drain is what this always meant to assert.
        awaitPendingDrained();
        assertThat(registry.resolve(callId, true)).isFalse();
    }

    /** Waits for {@link PendingConfirmationRegistry#await}'s {@code doFinally} cleanup, which is
     * ordered after the terminal signal reaches the subscriber and so cannot be observed
     * synchronously. Polls the map size rather than calling {@code resolve}: during the window
     * before cleanup runs, {@code resolve} would emit a value into the sink and change the state
     * being observed, turning the probe into a second source of flakiness. */
    private void awaitPendingDrained() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (registry.pendingCount() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(registry.pendingCount()).isZero();
    }

    @Test
    void cancellingTheSubscriptionStillCleansUpTheEntry() {
        UUID callId = UUID.randomUUID();
        Disposable subscription = registry.await(callId, Duration.ofSeconds(5)).subscribe();

        subscription.dispose();

        assertThat(registry.resolve(callId, true)).isFalse();
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

    /** Post-roadmap review B4: registration is deliberately eager, not deferred to subscription — a
     * {@code Mono.defer}-based version was tried and reverted after it broke
     * {@code ConversationToolConfirmationIntegrationTest} for real. {@code
     * ToolCallingChatService.handleToolCall} emits the {@code tool_call_pending} SSE event before it
     * ever subscribes to this {@code Mono} (sequenced behind it via {@code concatWith}); deferring
     * registration to subscription time opened a real window where a fast client's confirm request
     * could arrive before the server had registered anything, producing a genuine 404. Eager
     * registration — proven here by resolving successfully before ever subscribing — is what makes
     * "the client was told this is confirmable" and "the server can resolve it" atomic. */
    @Test
    void resolveSucceedsEvenBeforeSubscribing() throws Exception {
        UUID callId = UUID.randomUUID();
        Mono<Boolean> awaited = registry.await(callId, Duration.ofSeconds(5)); // built, not yet subscribed

        assertThat(registry.resolve(callId, true)).isTrue();

        assertThat(awaited.toFuture().get(2, TimeUnit.SECONDS)).isTrue();
    }

    /** Post-roadmap review B4: a minor denial-of-service consideration given no rate limiting exists
     * elsewhere — the map must reject cleanly past a configured bound rather than growing without
     * limit. */
    @Test
    void rejectsCleanlyOncePendingConfirmationsReachTheConfiguredBound() {
        ToolsProperties tightBound =
                new ToolsProperties(true, List.of(), Duration.ofSeconds(5), Duration.ofSeconds(5), 3, 2);
        PendingConfirmationRegistry boundedRegistry = new PendingConfirmationRegistry(tightBound);
        boundedRegistry.await(UUID.randomUUID(), Duration.ofSeconds(5)).subscribe();
        boundedRegistry.await(UUID.randomUUID(), Duration.ofSeconds(5)).subscribe();

        CompletableFuture<Boolean> overBound =
                boundedRegistry.await(UUID.randomUUID(), Duration.ofSeconds(5)).toFuture();

        assertThatThrownBy(() -> overBound.get(2, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .cause()
                .hasMessageContaining("2");
    }
}
