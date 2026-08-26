package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires {@link ChatProvider}/{@link EmbeddingProvider} for the {@code lmstudio} profile: Spring AI
 * hand-constructed here, never through its own Boot autoconfiguration, so its config surface never
 * leaks into application.yml beyond this class (docs/adr/0004-ai-provider-abstraction.md).
 */
@Configuration
@EnableConfigurationProperties(LmStudioProperties.class)
class LmStudioProviderConfiguration {

    @Bean
    @Profile("lmstudio")
    ChatProvider lmStudioChatProvider(LmStudioProperties properties, ObservationRegistry observationRegistry) {
        OpenAiChatModel chatModel =
                OpenAiChatModel.builder().options(chatOptions(properties)).build();
        return new LmStudioChatProvider(chatModel, properties.chatModel(), properties.timeout(), observationRegistry);
    }

    @Bean
    @Profile("lmstudio")
    EmbeddingProvider lmStudioEmbeddingProvider(LmStudioProperties properties) {
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .options(embeddingOptions(properties))
                .build();
        return new LmStudioEmbeddingProvider(embeddingModel, properties.embeddingModel());
    }

    /**
     * Package-private purely as a test seam, so {@code LmStudioProviderConfigurationTest} can assert
     * the configured timeout actually reaches the options object rather than re-deriving it — the
     * same visibility-relaxation reasoning ADR-0013 recorded for {@code RagPipeline.shouldAbstain}.
     *
     * <p><b>{@code .timeout(...)} is the line that makes {@code ai.provider.lmstudio.timeout} mean
     * anything</b> (post-roadmap review issue #65). Spring AI threads this options value through
     * {@code OpenAiSetup} into the HTTP client it builds for the model; leave it unset and
     * {@code AbstractOpenAiOptions.DEFAULT_TIMEOUT} — a hard 60 seconds — silently governs every
     * call instead. Measured, not assumed: with the property set to 15s, 300s and 600s in turn,
     * every request still died at 60.5s until this line existed.
     *
     * <p>An earlier attempt at this (issue #29) used
     * {@code httpClientBuilderCustomizer(b -> b.timeout(...))} and was recorded as fixed because a
     * live run "lasted longer". It did not work, and that was never evidence that it had.
     */
    static OpenAiChatOptions chatOptions(LmStudioProperties properties) {
        return OpenAiChatOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(apiKeyPlaceholder())
                .model(properties.chatModel())
                .timeout(properties.timeout())
                .build();
    }

    /** Embeddings inherit the same 60s default from the shared {@code AbstractOpenAiOptions} base, so
     * they need the same line. It has never bitten in practice — these are single-shot and fast — but
     * batch-embedding a large document is exactly the call that would eventually cross 60s. */
    static OpenAiEmbeddingOptions embeddingOptions(LmStudioProperties properties) {
        return OpenAiEmbeddingOptions.builder()
                .baseUrl(properties.baseUrl())
                .apiKey(apiKeyPlaceholder())
                .model(properties.embeddingModel())
                .timeout(properties.timeout())
                .build();
    }

    /** LM Studio doesn't check the key, but the openai-java SDK rejects a blank one at build time. */
    private static String apiKeyPlaceholder() {
        return "lm-studio";
    }
}
