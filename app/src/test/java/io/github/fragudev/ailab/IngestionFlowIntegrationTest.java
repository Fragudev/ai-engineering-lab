package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives the whole ingestion pipeline end to end under the {@code recorded} profile against real
 * Postgres and Kafka containers — what CI uses (docs/architecture.md #8). Uses the plain JDK
 * {@link HttpClient} throughout, including a hand-built multipart body ({@link IngestionTestHttp}):
 * this sidesteps needing to verify {@code RestTestClient}'s multipart API (new in Boot 4.1,
 * unconfirmed for this use), the same defensive choice Phase 1 made for its SSE test.
 *
 * <p>Fault-injection (DLT, retry exhaustion, job FAILED) is exercised separately in
 * {@link IngestionFailureIntegrationTest}, since it needs a different provider configuration.
 */
@Testcontainers
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class IngestionFlowIntegrationTest {

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
    void uploadIndexesDocumentEndToEndAndDedupsOnReupload() throws Exception {
        String content = "# Example\n\nThis is the first paragraph.\n\nThis is the second paragraph.\n";

        HttpResponse<String> uploadResponse =
                IngestionTestHttp.upload(httpClient, port, "example.md", "text/markdown", content, "example.md");
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        String jobLocation = uploadResponse.headers().firstValue("Location").orElseThrow();

        String finalJobBody = IngestionTestHttp.awaitTerminalJob(httpClient, port, jobLocation);
        assertThat(finalJobBody).contains("\"stage\":\"INDEXED\"");
        assertThat(finalJobBody).contains("\"lastError\":null");

        // Uploading the exact same bytes again does not create a new document or job (criterion 2).
        HttpResponse<String> duplicateResponse =
                IngestionTestHttp.upload(httpClient, port, "example.md", "text/markdown", content, "example.md");
        assertThat(duplicateResponse.statusCode()).isEqualTo(200);
        assertThat(duplicateResponse.headers().firstValue("Location")).isEmpty();
    }
}
