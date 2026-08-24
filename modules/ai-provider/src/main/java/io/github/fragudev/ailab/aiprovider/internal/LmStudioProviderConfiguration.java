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
        OpenAiChatModel chatModel = OpenAiChatModel.builder()
                .options(OpenAiChatOptions.builder()
                        .baseUrl(properties.baseUrl())
                        .apiKey(apiKeyPlaceholder())
                        .model(properties.chatModel())
                        .build())
                // Without this, ai.provider.lmstudio.timeout only bounded LmStudioChatProvider's own
                // outer Mono.timeout() — the underlying OkHttp client kept its own, much shorter
                // default read timeout, so a real 27B model's inter-token gaps (Phase 8's own
                // measured p50 was 26s, well past OkHttp's default) killed the stream with
                // ProviderUnavailableException before the configured timeout ever had a chance to
                // fire. Found for real running the live evaluation for issue #29, not a guess.
                .httpClientBuilderCustomizer(builder -> builder.timeout(properties.timeout()))
                .build();
        return new LmStudioChatProvider(chatModel, properties.chatModel(), properties.timeout(), observationRegistry);
    }

    @Bean
    @Profile("lmstudio")
    EmbeddingProvider lmStudioEmbeddingProvider(LmStudioProperties properties) {
        OpenAiEmbeddingModel embeddingModel = OpenAiEmbeddingModel.builder()
                .options(OpenAiEmbeddingOptions.builder()
                        .baseUrl(properties.baseUrl())
                        .apiKey(apiKeyPlaceholder())
                        .model(properties.embeddingModel())
                        .build())
                .build();
        return new LmStudioEmbeddingProvider(embeddingModel, properties.embeddingModel());
    }

    /** LM Studio doesn't check the key, but the openai-java SDK rejects a blank one at build time. */
    private static String apiKeyPlaceholder() {
        return "lm-studio";
    }
}
