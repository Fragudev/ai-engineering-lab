package io.github.fragudev.ailab.ingestion.internal;

import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs from the Kafka error handler's recoverer, once retries are exhausted — i.e. after the
 * failing attempt's own transaction has already rolled back. {@code REQUIRES_NEW} so this write
 * commits independently of that rollback.
 *
 * <p>Takes {@code correlationId}/{@code causationId} as raw UUIDs, not a typed {@code EventEnvelope}:
 * the recoverer runs on the Kafka error-handling path, which only ever sees the record's raw bytes
 * (Boot's default listener setup converts to the typed event later, at {@code @KafkaListener}
 * parameter binding) — see {@link IngestionFailureRecoverer}.
 */
@Component
class FailureRecording {

    private final IngestionJobRepository jobRepository;
    private final ApplicationEventPublisher eventPublisher;

    FailureRecording(IngestionJobRepository jobRepository, ApplicationEventPublisher eventPublisher) {
        this.jobRepository = jobRepository;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    void recordFailure(UUID correlationId, UUID causationId, UUID documentId, String stage, Exception exception) {
        // DefaultErrorHandler passes a wrapping ListenerExecutionFailedException ("Listener method
        // ... threw exception"); the useful diagnostic is the root cause's message.
        String message = NestedExceptionUtils.getMostSpecificCause(exception).getMessage();
        jobRepository.findByDocumentId(documentId).ifPresent(job -> job.fail(message));
        eventPublisher.publishEvent(DocumentFailedEvent.of(correlationId, causationId, documentId, stage, message));
    }
}
