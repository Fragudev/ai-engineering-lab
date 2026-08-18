package io.github.fragudev.ailab.aiprovider.internal;

import io.github.fragudev.ailab.aiprovider.ChatProvider;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires {@link ChatProvider}/{@link EmbeddingProvider} for the {@code recorded} profile: fixture
 * replay, no network, no live model — what CI uses (docs/architecture.md #8).
 */
@Configuration
class RecordedProviderConfiguration {

    @Bean
    @Profile("recorded")
    ChatProvider recordedChatProvider() {
        return new RecordedChatProvider();
    }

    @Bean
    @Profile("recorded")
    EmbeddingProvider recordedEmbeddingProvider() {
        return new RecordedEmbeddingProvider();
    }
}
