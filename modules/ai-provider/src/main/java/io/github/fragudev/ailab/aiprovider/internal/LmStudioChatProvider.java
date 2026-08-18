package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.ChatChunk;
import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.ChatRequest;
import io.github.fragudev.ailab.aiprovider.ChatResponse;
import io.github.fragudev.ailab.aiprovider.ProviderCapabilities;
import io.github.fragudev.ailab.aiprovider.TokenUsage;
import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.ai.chat.model.ChatModel;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Real LM Studio adapter: OpenAI-compatible API, hand-constructed (never Boot-autoconfigured). */
final class LmStudioChatProvider implements ChatProvider {

    private static final String PROVIDER_NAME = "lmstudio";

    private final ChatModel chatModel;
    private final String modelName;
    private final Duration timeout;
    private final ObservationRegistry observationRegistry;

    LmStudioChatProvider(
            ChatModel chatModel, String modelName, Duration timeout, ObservationRegistry observationRegistry) {
        this.chatModel = chatModel;
        this.modelName = modelName;
        this.timeout = timeout;
        this.observationRegistry = observationRegistry;
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        Observation observation = newObservation();
        return observation.observe(() -> {
            Instant start = Instant.now();
            try {
                org.springframework.ai.chat.model.ChatResponse response =
                        chatModel.call(PromptMapping.toPrompt(request));
                ChatResponse result = toChatResponse(response, Duration.between(start, Instant.now()));
                tagObservation(observation, result);
                return result;
            } catch (RuntimeException e) {
                // The openai-java SDK's exception hierarchy (com.openai.errors.*) is not always the
                // exact type that reaches here — it can arrive wrapped. Anything that escapes a
                // provider call is, by this method's contract, a provider failure.
                throw new ProviderUnavailableException(PROVIDER_NAME, e);
            }
        });
    }

    @Override
    public Flux<ChatChunk> stream(ChatRequest request) {
        Instant start = Instant.now();
        StringBuilder aggregateText = new StringBuilder();
        AtomicReference<org.springframework.ai.chat.model.ChatResponse> lastChunk = new AtomicReference<>();
        Observation observation = newObservation();

        return Flux.defer(() -> {
                    observation.start();
                    return chatModel.stream(PromptMapping.toPrompt(request));
                })
                .timeout(timeout)
                .doOnNext(lastChunk::set)
                .map(chunk -> {
                    String delta = extractText(chunk);
                    aggregateText.append(delta);
                    return ChatChunk.delta(delta);
                })
                .concatWith(Mono.defer(() -> {
                    ChatResponse aggregate = toAggregateChatResponse(
                            lastChunk.get(), aggregateText.toString(), Duration.between(start, Instant.now()));
                    tagObservation(observation, aggregate);
                    return Mono.just(ChatChunk.last(aggregate));
                }))
                .onErrorMap(TimeoutException.class, e -> new ProviderTimeoutException(PROVIDER_NAME, timeout))
                // Verified live against an unreachable endpoint: the SDK's connection failure does
                // not always surface here as the exact com.openai.errors.OpenAIIoException type —
                // it can arrive wrapped by Reactor. Anything that isn't already one of this
                // adapter's own typed exceptions is, by this method's contract, a provider failure.
                .onErrorMap(
                        error -> !(error instanceof ProviderTimeoutException),
                        error -> new ProviderUnavailableException(PROVIDER_NAME, error))
                .doFinally(signal -> observation.stop());
    }

    @Override
    public ProviderCapabilities capabilities() {
        // Small local models support tool calling unreliably at best; the tools module (Phase 5)
        // falls back to structured-output parsing rather than trusting this blindly.
        return new ProviderCapabilities(false, false, 8192);
    }

    private Observation newObservation() {
        return Observation.createNotStarted("gen_ai.chat", observationRegistry)
                .lowCardinalityKeyValue("gen_ai.system", PROVIDER_NAME)
                .lowCardinalityKeyValue("gen_ai.request.model", modelName);
    }

    private static void tagObservation(Observation observation, ChatResponse response) {
        observation
                .lowCardinalityKeyValue("gen_ai.response.model", response.model())
                .highCardinalityKeyValue(
                        "gen_ai.usage.prompt_tokens",
                        String.valueOf(response.usage().promptTokens()))
                .highCardinalityKeyValue(
                        "gen_ai.usage.completion_tokens",
                        String.valueOf(response.usage().completionTokens()));
    }

    private static String extractText(org.springframework.ai.chat.model.ChatResponse response) {
        if (response.getResult() == null || response.getResult().getOutput() == null) {
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private ChatResponse toChatResponse(org.springframework.ai.chat.model.ChatResponse response, Duration latency) {
        String content = extractText(response);
        return new ChatResponse(content, resolveModelName(response), toTokenUsage(response), latency, BigDecimal.ZERO);
    }

    private ChatResponse toAggregateChatResponse(
            org.springframework.ai.chat.model.ChatResponse lastChunk, String content, Duration latency) {
        return new ChatResponse(
                content, resolveModelName(lastChunk), toTokenUsage(lastChunk), latency, BigDecimal.ZERO);
    }

    private String resolveModelName(org.springframework.ai.chat.model.ChatResponse response) {
        if (response != null
                && response.getMetadata() != null
                && response.getMetadata().getModel() != null) {
            String model = response.getMetadata().getModel();
            if (!model.isBlank()) {
                return model;
            }
        }
        return modelName;
    }

    private static TokenUsage toTokenUsage(org.springframework.ai.chat.model.ChatResponse response) {
        if (response == null
                || response.getMetadata() == null
                || response.getMetadata().getUsage() == null) {
            return TokenUsage.zero();
        }
        var usage = response.getMetadata().getUsage();
        return new TokenUsage(usage.getPromptTokens(), usage.getCompletionTokens());
    }
}
