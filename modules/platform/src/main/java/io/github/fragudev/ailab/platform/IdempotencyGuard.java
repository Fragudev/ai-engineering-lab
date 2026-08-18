package io.github.fragudev.ailab.platform;

import io.github.fragudev.ailab.platform.internal.ProcessedEventRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * At-least-once Kafka delivery means every consumer must be idempotent (docs/adr/0005-kafka.md).
 * Call {@link #isNewEvent} first in a consumer's own transactional method; if it returns
 * {@code false}, the event was already processed and the rest of the method should be a no-op.
 *
 * <p>Deliberately a check-then-insert, not insert-and-catch-the-constraint-violation: on Postgres, a
 * failed statement aborts the rest of the current transaction, so recovering from a caught
 * constraint violation would need a savepoint. A genuine race (two redeliveries processed
 * concurrently) instead surfaces as a real constraint-violation failure on the second flush, which
 * Spring Kafka's error handler retries — and the retry's check-then-insert then correctly sees the
 * row the other attempt wrote and reports "already processed". Self-healing, not silently masked.
 */
@Component
public class IdempotencyGuard {

    private final ProcessedEventRepository repository;

    public IdempotencyGuard(ProcessedEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public boolean isNewEvent(String consumerGroup, UUID eventId) {
        if (repository.existsByConsumerGroupAndEventId(consumerGroup, eventId)) {
            return false;
        }
        repository.recordProcessed(consumerGroup, eventId);
        return true;
    }
}
