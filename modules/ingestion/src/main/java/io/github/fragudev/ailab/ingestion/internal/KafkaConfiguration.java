package io.github.fragudev.ailab.ingestion.internal;

import io.github.fragudev.ailab.shared.NonRetryableIngestionException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

/**
 * Retry then dead-letter (docs/adr/0005-kafka.md): exponential backoff with jitter, then the
 * topic's {@code .dlt} via {@link IngestionFailureRecoverer}. {@link NonRetryableIngestionException}
 * (bad MIME type, corrupt file) skips retry entirely. Boot's autoconfigured listener container
 * factory picks this bean up automatically — no need to hand-construct the factory itself. Kafka
 * observation (needed for criterion 5, "one connected trace") is opt-in even with the OTel starter
 * present, so it's enabled explicitly via {@code spring.kafka.template.observation-enabled} /
 * {@code spring.kafka.listener.observation-enabled} in application.yml, not here.
 */
@Configuration
class KafkaConfiguration {

    @Bean
    DefaultErrorHandler ingestionErrorHandler(IngestionFailureRecoverer recoverer) {
        var backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8000L);
        backOff.setJitter(500L);

        DefaultErrorHandler errorHandler = new DefaultErrorHandler(recoverer, backOff);
        errorHandler.addNotRetryableExceptions(NonRetryableIngestionException.class);
        return errorHandler;
    }
}
