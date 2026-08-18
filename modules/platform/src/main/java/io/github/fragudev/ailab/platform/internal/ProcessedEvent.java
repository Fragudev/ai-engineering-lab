package io.github.fragudev.ailab.platform.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;

/**
 * Redelivery is a no-op: a unique constraint on {@code (consumer_group, event_id)} gives the same
 * guarantee as a composite primary key, without JPA composite-key mapping risk for what is, at
 * bottom, a single append-only fact per (consumer, event) pair.
 */
@Entity
@Table(name = "processed_event", uniqueConstraints = @UniqueConstraint(columnNames = {"consumer_group", "event_id"}))
class ProcessedEvent {

    @Id
    private UUID id;

    @Column(name = "consumer_group")
    private String consumerGroup;

    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "processed_at")
    private Instant processedAt;

    protected ProcessedEvent() {
        // JPA
    }

    ProcessedEvent(String consumerGroup, UUID eventId) {
        this.id = UUID.randomUUID();
        this.consumerGroup = consumerGroup;
        this.eventId = eventId;
        this.processedAt = Instant.now();
    }
}
