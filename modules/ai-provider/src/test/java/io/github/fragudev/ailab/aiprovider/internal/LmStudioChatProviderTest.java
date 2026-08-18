package io.github.fragudev.ailab.aiprovider.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.ChatMessage;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.micrometer.observation.ObservationRegistry;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/**
 * A provider timeout must surface as a typed {@link ProviderTimeoutException}, not a hung request
 * (roadmap Phase 1, acceptance criterion 4). Exercised directly against the adapter with a fake
 * {@link ChatModel} that never emits, rather than a real network call.
 */
class LmStudioChatProviderTest {

    @Test
    void streamTimesOutWithATypedException() {
        ChatModel neverRespondingModel = new ChatModel() {
            @Override
            public org.springframework.ai.chat.model.ChatResponse call(Prompt prompt) {
                throw new UnsupportedOperationException("not exercised by this test");
            }

            @Override
            public Flux<org.springframework.ai.chat.model.ChatResponse> stream(Prompt prompt) {
                return Flux.never();
            }
        };

        LmStudioChatProvider provider =
                new LmStudioChatProvider(neverRespondingModel, "test-model", Duration.ofMillis(50), ObservationRegistry.NOOP);

        ChatRequest request = new ChatRequest(List.of(ChatMessage.user("hello")));

        StepVerifier.create(provider.stream(request))
                .expectErrorMatches(error -> {
                    assertThat(error).isInstanceOf(ProviderTimeoutException.class);
                    return true;
                })
                .verify(Duration.ofSeconds(2));
    }
}
