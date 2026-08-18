package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.Embedding;
import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.util.List;
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

    @Test
    void embeddingFailureExhaustsRetriesAndFailsTheJob() throws Exception {
        HttpResponse<String> uploadResponse = IngestionTestHttp.upload(
                httpClient, port, "fails.txt", "text/plain", "This will fail to embed.", "fails.txt");
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        String jobLocation = uploadResponse.headers().firstValue("Location").orElseThrow();

        String finalJobBody = IngestionTestHttp.awaitTerminalJob(httpClient, port, jobLocation);

        assertThat(finalJobBody).contains("\"stage\":\"FAILED\"");
        assertThat(finalJobBody).contains("Simulated embedding failure");
    }

    @TestConfiguration
    static class FailingEmbeddingProviderConfig {

        @Bean
        @Primary
        EmbeddingProvider failingEmbeddingProvider() {
            return new EmbeddingProvider() {
                @Override
                public List<Embedding> embed(List<String> texts) {
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
