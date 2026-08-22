package io.github.fragudev.ailab.tools.internal;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * In-memory holding pen for RAG-sourced tool calls awaiting {@code POST
 * /api/v1/tool-calls/{callId}:confirm} (docs/threat-model.md T2). Deliberately not resumable across
 * an app restart — the map is in-memory and the owning SSE connection dies with the process anyway;
 * real cross-restart resumability is Phase 6/{@code workflow}'s job, not silently promised here.
 */
@Component
public class PendingConfirmationRegistry {

    private final Map<UUID, Sinks.One<Boolean>> pending = new ConcurrentHashMap<>();

    /** Registers {@code callId} and returns a {@code Mono} that completes with the confirmation
     * decision once {@link #resolve} is called, or errors with a {@link java.util.concurrent.TimeoutException}
     * if {@code timeout} elapses first — deliberately an error, not a {@code false} fallback value:
     * {@code ToolCallingChatService} needs to tell "the user explicitly rejected this" (outcome
     * {@code DENIED}) apart from "nobody answered in time" (outcome {@code TIMEOUT}), and a real
     * live run of the confirmation flow caught this exact conflation when both cases first shared a
     * single {@code false} signal. */
    public Mono<Boolean> await(UUID callId, Duration timeout) {
        Sinks.One<Boolean> sink = Sinks.one();
        pending.put(callId, sink);
        return sink.asMono().timeout(timeout).doFinally(signal -> pending.remove(callId));
    }

    /** @return {@code true} if {@code callId} was pending and is now resolved; {@code false} if it
     *     was unknown, already resolved, or already timed out. */
    public boolean resolve(UUID callId, boolean approved) {
        Sinks.One<Boolean> sink = pending.get(callId);
        if (sink == null) {
            return false;
        }
        Sinks.EmitResult result = sink.tryEmitValue(approved);
        return result.isSuccess();
    }
}
