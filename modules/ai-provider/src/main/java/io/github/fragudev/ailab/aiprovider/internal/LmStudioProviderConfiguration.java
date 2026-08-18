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
