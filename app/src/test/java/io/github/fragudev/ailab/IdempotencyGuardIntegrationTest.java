package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.platform.IdempotencyGuard;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Redelivering a consumed event is a no-op (roadmap Phase 2, acceptance criterion 4). Exercised
 * directly against {@link IdempotencyGuard} with a real Postgres — the exact mechanism every
 * ingestion consumer relies on — rather than through a full Kafka round trip: crafting a raw,
 * hand-serialized Kafka message that exactly matches what Spring's JSON (de)serialization + type
 * headers produce is a meaningfully separate risk from the idempotency guarantee itself, which this
 * test proves precisely and without that risk. The two other ingestion tests already prove real
 * Kafka consumption works end to end.
 */
@Testcontainers
@ActiveProfiles("recorded")
@SpringBootTest
class IdempotencyGuardIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @Autowired
    private IdempotencyGuard idempotencyGuard;

    @Test
    void redeliveringTheSameEventIsANoOp() {
        String consumerGroup = "test-consumer";
        UUID eventId = UUID.randomUUID();

        assertThat(idempotencyGuard.isNewEvent(consumerGroup, eventId)).isTrue();
        assertThat(idempotencyGuard.isNewEvent(consumerGroup, eventId)).isFalse();
        assertThat(idempotencyGuard.isNewEvent(consumerGroup, eventId)).isFalse();
    }

    @Test
    void theSameEventIdIsIndependentPerConsumerGroup() {
        UUID eventId = UUID.randomUUID();

        assertThat(idempotencyGuard.isNewEvent("consumer-a", eventId)).isTrue();
        assertThat(idempotencyGuard.isNewEvent("consumer-b", eventId)).isTrue();
    }
}
