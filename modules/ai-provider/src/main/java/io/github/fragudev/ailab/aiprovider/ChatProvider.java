package io.github.fragudev.ailab.aiprovider;

import io.github.fragudev.ailab.shared.ProviderException;
import reactor.core.publisher.Flux;

/**
 * Project-owned chat completion interface. Spring AI (or any other client library) is an
 * implementation detail hidden inside adapters under {@code internal}; this type is the only thing
 * the rest of the codebase depends on (docs/adr/0004-ai-provider-abstraction.md).
 */
public interface ChatProvider {

    /** Non-streaming completion. */
    ChatResponse complete(ChatRequest request);

    /**
     * Streaming completion: a delta chunk per token/fragment, terminated by one {@link ChatChunk}
     * with {@code last() == true} carrying the full aggregate.
     *
     * @throws ProviderException (as an error signal on the Flux) on timeout or connection failure
     */
    Flux<ChatChunk> stream(ChatRequest request);

    ProviderCapabilities capabilities();
}
