package io.github.fragudev.ailab.platform.internal;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, UUID> {

    boolean existsByConsumerGroupAndEventId(String consumerGroup, UUID eventId);

    /** Package-private {@link ProcessedEvent} stays unreachable outside {@code .internal}. */
    default void recordProcessed(String consumerGroup, UUID eventId) {
        save(new ProcessedEvent(consumerGroup, eventId));
    }
}
