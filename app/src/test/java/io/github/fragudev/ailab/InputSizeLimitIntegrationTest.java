package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Regression coverage for issue #23 (post-roadmap review S3): both input-size limits
 * ({@code spring.servlet.multipart.max-file-size}, {@link SendMessageRequest}'s {@code @Size})
 * must reject an oversized request with a clean Problem Details body, not a stack trace or the
 * default Spring Boot error page — {@link ApiExceptionHandler#handleMaxUploadSizeExceeded} and the
 * existing {@code MethodArgumentNotValidException} handler are what this test actually proves are
 * wired up, not just that the size limits are configured.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class InputSizeLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @LocalServerPort
    private int port;

    @Autowired
    private RestTestClient restClient;

    @Test
    void oversizedUploadIsRejectedWithProblemDetailsNotAStackTrace() throws Exception {
        // One byte over application.yml's configured 10 MB max-file-size.
        String hugeContent = "x".repeat(10 * 1024 * 1024 + 1);
        var response = IngestionTestHttp.upload(
                HttpClient.newHttpClient(), port, "huge.md", "text/markdown", hugeContent, "huge.md");

        assertThat(response.statusCode()).isEqualTo(413);
        Map<String, Object> body = readProblemDetailsBody(response.body());
        assertThat(body).containsEntry("status", 413);
        assertThat(body).doesNotContainKeys("trace", "exception");
    }

    @Test
    void oversizedChatMessageIsRejectedWithProblemDetailsNotAStackTrace() {
        UUID conversationId = restClient
                .post()
                .uri("/api/v1/conversations")
                .exchange()
                .expectStatus()
                .isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody()
                .id();

        // One char over SendMessageRequest's @Size(max = 8000).
        String hugeMessage = "a".repeat(8001);
        Map<String, Object> body = restClient
                .post()
                .uri("/api/v1/conversations/" + conversationId + "/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new SendMessageRequest(hugeMessage, null))
                .exchange()
                .expectStatus()
                .isEqualTo(400)
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(body).containsEntry("status", 400);
        assertThat(body).doesNotContainKeys("trace", "exception");
    }

    private static Map<String, Object> readProblemDetailsBody(String rawBody) {
        return new ObjectMapper().readValue(rawBody, new TypeReference<Map<String, Object>>() {});
    }
}
