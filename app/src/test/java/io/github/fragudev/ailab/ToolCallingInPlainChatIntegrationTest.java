package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.ChatRole;
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
 * Plain (non-RAG) chat gets tool calling too, as of Phase 5 — this drives the fixture-backed
 * calculator round trip ({@code fixtures/chat/fixtures.json}'s {@code "what is 12 times 7"} entry)
 * end to end under the {@code recorded} profile: the model "calls" the calculator, the tool
 * actually executes (a real recursive-descent evaluation of {@code "12 * 7"}), the result is fed
 * back, and the model's follow-up ({@code fixtures.json}'s {@code followUp}) becomes the persisted
 * assistant reply. Same raw-{@link HttpClient} SSE pattern as {@code ConversationFlowIntegrationTest}.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ToolCallingInPlainChatIntegrationTest {

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
    void calculatorRoundTripStreamsToolEventsAndPersistsTheFollowUpAnswer() throws Exception {
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

        HttpResponse<String> streamResponse = postMessage(conversationId, "what is 12 times 7");

        assertThat(streamResponse.statusCode()).isEqualTo(200);
        assertThat(streamResponse.body()).contains("event:tool_call");
        assertThat(streamResponse.body()).contains("calculator");
        assertThat(streamResponse.body()).contains("event:tool_result");
        assertThat(streamResponse.body()).contains("\"outcome\":\"OK\"");
        assertThat(streamResponse.body()).contains("84.0");
        assertThat(streamResponse.body()).contains("event:token");
        assertThat(streamResponse.body()).contains("event:usage");
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
        assertThat(messages.get(1).role()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(messages.get(1).content()).isEqualTo("12 times 7 is 84.");
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
