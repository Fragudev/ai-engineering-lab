package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves both Phase 7 acceptance criteria against a real MCP handshake — self-connected, since no
 * independent third-party MCP server exists in this project's infrastructure
 * (docs/adr/0011-mcp-tool-exposure-boundaries.md): (1) the app's own {@code /mcp} endpoint is a real
 * MCP server another client can discover and invoke tools on — proven here by the app's own MCP
 * client doing exactly that; (2) the discovered {@code mcp:self:*} tools are usable in chat, and
 * {@link io.github.fragudev.ailab.tools.ToolDefinition#alwaysRequiresConfirmation()} means the
 * confirmation gate fires on the very first call of a plain-chat turn — the opposite of
 * knowledge-base-search's ungated-first-call default (docs/threat-model.md T9).
 *
 * <p>{@code webEnvironment = DEFINED_PORT}, not this project's usual {@code RANDOM_PORT}: the MCP
 * client's self-connect URL has to be known before the context starts, since
 * {@code spring.ai.mcp.client.streamable-http.connections.self.url} is read at bean-construction
 * time (an already-documented, deliberate deviation — see the plan's Verification section).
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@TestPropertySource(
        properties = {
            "server.port=18089",
            "spring.ai.mcp.server.name=self",
            "spring.ai.mcp.client.enabled=true",
            "spring.ai.mcp.client.streamable-http.connections.self.url=http://localhost:18089",
            "spring.ai.mcp.client.streamable-http.connections.self.endpoint=/mcp",
            "ai.tools.confirmation-timeout=3s"
        })
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
class McpServerAndClientIntegrationTest {

    private static final int PORT = 18089;
    private static final Pattern CALL_ID_PATTERN = Pattern.compile("\"callId\":\"([0-9a-fA-F-]{36})\"");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @Autowired
    private RestTestClient restClient;

    @Test
    void discoversItsOwnToolsOverMcpAndUsesOneInChatWithMandatoryConfirmation() throws Exception {
        awaitMcpClientToolsRegistered();

        UUID conversationId = createPlainConversation();

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<UUID> callIdFuture = new CompletableFuture<>();
            var linesFuture = CompletableFuture.supplyAsync(
                    () -> readSseLines(conversationId, "what is 6 times 7 via mcp", callIdFuture), reader);

            UUID callId = callIdFuture.get(10, TimeUnit.SECONDS);

            restClient
                    .post()
                    .uri("/api/v1/tool-calls/" + callId + ":confirm")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ToolConfirmRequest(true))
                    .exchange()
                    .expectStatus()
                    .isOk();

            List<String> allLines = linesFuture.get(10, TimeUnit.SECONDS);
            String body = String.join("\n", allLines);

            assertThat(body).contains("event:tool_call_pending");
            assertThat(body).contains("\"toolName\":\"mcp:self:calculator\"");
            assertThat(body).contains("event:tool_result");
            assertThat(body).contains("\"result\":{\"result\":42.0}");
            assertThat(body).contains("event:done");
        } finally {
            reader.shutdownNow();
        }
    }

    /** {@code mcp.internal.McpClientToolRegistrar} runs on {@code ApplicationReadyEvent}, which has
     * already fired by the time a {@code @SpringBootTest} hands control to a test method — but the
     * MCP handshake it triggers is still a real network round-trip, so this polls rather than
     * assuming it's instantaneous. */
    private void awaitMcpClientToolsRegistered() {
        await().atMost(Duration.ofSeconds(10))
                .pollInterval(Duration.ofMillis(100))
                .until(() -> {
                    List<ToolDefinitionResponse> tools = restClient
                            .get()
                            .uri("/api/v1/tools")
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(new ParameterizedTypeReference<List<ToolDefinitionResponse>>() {})
                            .returnResult()
                            .getResponseBody();
                    return tools != null
                            && tools.stream().anyMatch(tool -> tool.name().equals("mcp:self:calculator"));
                });
    }

    private UUID createPlainConversation() {
        return restClient
                .post()
                .uri("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateConversationRequest(null))
                .exchange()
                .expectStatus()
                .isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    private List<String> readSseLines(UUID conversationId, String content, CompletableFuture<UUID> callIdFuture) {
        List<String> collected = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://localhost:%d/api/v1/conversations/%s/messages".formatted(PORT, conversationId)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"%s\"}".formatted(content)))
                    .build();
            HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());

            boolean sawPendingEvent = false;
            for (String line : (Iterable<String>) response.body()::iterator) {
                collected.add(line);
                if (line.startsWith("event:tool_call_pending")) {
                    sawPendingEvent = true;
                } else if (sawPendingEvent && line.startsWith("data:")) {
                    Matcher matcher = CALL_ID_PATTERN.matcher(line);
                    if (matcher.find() && !callIdFuture.isDone()) {
                        callIdFuture.complete(UUID.fromString(matcher.group(1)));
                    }
                    sawPendingEvent = false;
                }
            }
        } catch (Exception e) {
            callIdFuture.completeExceptionally(e);
            throw new RuntimeException(e);
        }
        return collected;
    }
}
