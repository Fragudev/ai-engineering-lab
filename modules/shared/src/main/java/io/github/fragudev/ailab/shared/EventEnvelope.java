package io.github.fragudev.ailab.shared;

import java.time.Instant;
import java.util.UUID;

/**
 * Common shape every Kafka event payload class implements (docs/events/README.md). {@code eventId}
 * is the idempotency key consumers deduplicate on; {@code correlationId} is constant across an
 * entire causal chain, {@code causationId} is the {@code eventId} that directly produced this one.
 */
public interface EventEnvelope {

    UUID eventId();

    String type();

    String source();

    String subject();

    Instant time();

    UUID correlationId();

    UUID causationId();
}
