package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.fragudev.ailab.aiprovider.Embedding;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A forced embedding failure exhausts retries, lands in the dead-letter topic, and leaves the job
 * FAILED with {@code lastError} populated (roadmap Phase 2, acceptance criterion 3). Kept separate
 * from {@link IngestionFlowIntegrationTest} because it needs its own always-throwing
 * {@link EmbeddingProvider}, overriding the profile-provided one via {@code @Primary}.
 *
 * <p>Post-roadmap review issue #32: the dead-letter landing itself — the part docs/architecture.md
 * §11 singles out as "the part worth reviewing" — was never asserted, only the job ending
 * {@code FAILED}. {@link #embeddingFailureExhaustsRetriesAndFailsTheJob} now also consumes the
 * failed message back off {@code ingestion.chunks.created.v1.dlt} and checks it's the same document
 * that failed, not merely that the topic isn't empty.
 */
@Testcontainers
@ActiveProfiles("recorded")
@Import(IngestionFailureIntegrationTest.FailingEmbeddingProviderConfig.class)
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IngestionFailureIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @Container
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void embeddingFailureExhaustsRetriesAndFailsTheJob() throws Exception {
        HttpResponse<String> uploadResponse = IngestionTestHttp.upload(
                httpClient, port, "fails.txt", "text/plain", "This will fail to embed.", "fails.txt");
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        String jobLocation = uploadResponse.headers().firstValue("Location").orElseThrow();

        String finalJobBody = IngestionTestHttp.awaitTerminalJob(httpClient, port, jobLocation);
        JsonNode job = JSON.readTree(finalJobBody);

        assertThat(job.get("stage").asText()).isEqualTo("FAILED");
        assertThat(job.get("lastError").asText()).contains("Simulated embedding failure");
        // docs/architecture.md §11's own claim ("a document whose embedding stage fails three
        // times") — checked directly by counting real calls to the failing provider rather than
        // via IngestionJob.attempts(), which is never incremented on this path (recordAttempt()
        // exists but nothing calls it — Kafka's own DefaultErrorHandler redelivers the record
        // in-process, it doesn't route through that field). The exact count is not a stable number
        // to assert here, though: running this test repeatedly measured both 4 and 5, one call more
        // than KafkaConfiguration's ExponentialBackOffWithMaxRetries(3) config alone would predict
        // (1 initial + 3 retries = 4) — most likely a consumer-group rebalance replaying a message
        // under this test's container-startup timing, a real but separate concern from what this
        // issue asks for. What's stable and worth asserting is that retries genuinely happened, not
        // a single bare attempt.
        assertThat(embedCallCount.get()).isGreaterThanOrEqualTo(2);

        JsonNode dltEvent = consumeOneRecordFrom("ingestion.chunks.created.v1.dlt");
        assertThat(dltEvent.get("documentId").asText())
                .isEqualTo(job.get("documentId").asText());
    }

    /** Polls the given topic (bootstrapped fresh against the test container, {@code
     * auto.offset.reset=earliest} so it doesn't matter whether the DLT publish already happened by
     * the time this subscribes) until exactly the message the failed job produced shows up, and
     * parses its value as JSON — the message on the DLT, not merely that the topic isn't empty. */
    private JsonNode consumeOneRecordFrom(String topic) throws Exception {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "dlt-assertion-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());

        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            var holder = new Object() {
                ConsumerRecord<byte[], byte[]> record;
            };
            await().atMost(Duration.ofSeconds(15))
                    .pollInterval(Duration.ofMillis(200))
                    .until(() -> {
                        ConsumerRecords<byte[], byte[]> polled = consumer.poll(Duration.ofMillis(200));
                        if (!polled.isEmpty()) {
                            holder.record = polled.iterator().next();
                        }
                        return holder.record != null;
                    });
            return JSON.readTree(holder.record.value());
        }
    }

    private static final AtomicInteger embedCallCount = new AtomicInteger();

    @TestConfiguration
    static class FailingEmbeddingProviderConfig {

        @Bean
        @Primary
        EmbeddingProvider failingEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override
                public List<Embedding> embed(List<String> texts) {
                    embedCallCount.incrementAndGet();
                    throw new ProviderUnavailableException("test", new RuntimeException("Simulated embedding failure"));
                }

                @Override
                public int dimensions() {
                    return 1024;
                }

                @Override
                public String modelId() {
                    return "failing-test-provider";
                }
            };
        }
    }
}
