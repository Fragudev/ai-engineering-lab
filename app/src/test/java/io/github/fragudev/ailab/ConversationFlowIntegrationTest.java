package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.ChatRole;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives the chat vertical slice end to end under the {@code recorded} profile — no live model
 * server, deterministic, what CI uses (docs/architecture.md #8). The streaming call itself goes
 * through the plain JDK {@link HttpClient} rather than {@code RestTestClient}, because this is a
 * POST with a body, which native browser SSE can't do either (see the minimal UI's own comment) —
 * and because it keeps this test's grip on the raw SSE framing unambiguous.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConversationFlowIntegrationTest {

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
    void streamsAssistantReplyAndPersistsUsage() throws Exception {
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

        HttpResponse<String> streamResponse = postMessage(conversationId, "hello");

        assertThat(streamResponse.statusCode()).isEqualTo(200);
        // The fixture text streams token-by-token, one word per SSE "data:" line, so a multi-word
        // phrase never appears contiguous in the raw body — check a single whole word instead, and
        // rely on the persisted-message assertions below for the full reconstructed text.
        assertThat(streamResponse.body()).contains("event:token");
        assertThat(streamResponse.body()).contains("captured");
        assertThat(streamResponse.body()).contains("recorded-fixture");
        assertThat(streamResponse.body()).contains("event:done");

        List<MessageResponse> messages = restClient
                .get()
                .uri("/api/v1/conversations/" + conversationId + "/messages")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<MessageResponse>>() {})
                .returnResult()
                .getResponseBody();

        assertThat(messages).hasSize(2);
        assertThat(messages.get(0).role()).isEqualTo(ChatRole.USER);
        assertThat(messages.get(0).content()).isEqualTo("hello");
        assertThat(messages.get(1).role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(messages.get(1).model()).isEqualTo("recorded-fixture");
        assertThat(messages.get(1).promptTokens()).isPositive();
        assertThat(messages.get(1).completionTokens()).isPositive();
        assertThat(messages.get(1).estimatedCostUsd()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void returnsNotFoundForAnUnknownConversation() {
        restClient
                .get()
                .uri("/api/v1/conversations/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(404);
    }

    private HttpResponse<String> postMessage(UUID conversationId, String content) throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/conversations/%s/messages".formatted(port, conversationId)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"%s\"}".formatted(content)))
                .build();
        return client.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
