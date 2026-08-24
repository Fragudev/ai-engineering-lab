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
import org.junit.jupiter.api.Tag;
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
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Automates the exact journey {@code scripts/demo.sh} narrates by hand (post-roadmap review issue
 * #34): plain chat, real async ingestion into a hybrid-RAG-cited answer, a confirmed tool call
 * reached over a real MCP handshake, and a persisted six-stage agentic workflow — one continuous
 * flow against one running app instance, the same shape as the script itself, not four isolated
 * capability tests. Everything else in {@code app/src/test} already covers each of these
 * capabilities individually and in more depth; what none of it proves on its own is that the full
 * story chains together end to end the way a real user (or {@code scripts/demo.sh}) would drive it.
 *
 * <p><b>Kept in sync with {@code scripts/demo.sh}, not driving it</b> (the two are different runtime
 * shapes — a bash script against a live `docker compose` stack vs. a JUnit/Testcontainers suite —
 * so one can't literally drive the other): every query and seed-document string below is copied
 * verbatim from the script (and from {@link RagFlowIntegrationTest}/{@link WorkflowRunIntegrationTest},
 * which the script's own comments already point at as its source of truth for those exact strings).
 * If {@code scripts/demo.sh} or {@code DEMO.md} changes what capability it demonstrates or what
 * fixture text it uses, this test needs the matching change — there is no automated link between
 * them, only this comment.
 *
 * <p>Tagged {@code e2e} so it runs in its own CI job (`.github/workflows/ci.yml`) rather than the
 * default {@code build} job's `./mvnw verify` — {@code app/pom.xml}'s Surefire configuration excludes
 * this tag by default. Real Postgres and Kafka containers, the actual async upload → parse → chunk →
 * embed → index pipeline (not the direct-seed shortcut {@link RagFlowIntegrationTest}/
 * {@link WorkflowRunIntegrationTest} use for speed) — this tier is specifically about proving the
 * real pipeline connects to everything downstream of it, which those tests deliberately don't
 * exercise.
 */
@Tag("e2e")
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@TestPropertySource(
        properties = {
            "server.port=18090",
            "spring.ai.mcp.server.name=self",
            "spring.ai.mcp.client.enabled=true",
            "spring.ai.mcp.client.streamable-http.connections.self.url=http://localhost:18090",
            "spring.ai.mcp.client.streamable-http.connections.self.endpoint=/mcp",
            "ai.tools.confirmation-timeout=10s"
        })
@SpringBootTest(webEnvironment = WebEnvironment.DEFINED_PORT)
class DemoJourneyEndToEndTest {

    private static final int PORT = 18090;
    private static final Pattern CALL_ID_PATTERN = Pattern.compile("\"callId\":\"([0-9a-fA-F-]{36})\"");

    // Same exact strings scripts/demo.sh uses (and, per its own comments, the same ones
    // RagFlowIntegrationTest / WorkflowRunIntegrationTest use) — the `recorded` profile's fixtures
    // are keyed to this exact text, and RecordedEmbeddingProvider's hash-seeded embeddings only
    // guarantee a retrieval hit for an exact content match.
    private static final String RAG_DOC_TEXT = "What does consumer lag measure?";
    private static final String WORKFLOW_SUB_QUERY_A = "What is the boiling point of water?";
    private static final String WORKFLOW_SUB_QUERY_B = "What is dry ice made of?";
    private static final String WORKFLOW_QUERY = "What is the boiling point of water and what is dry ice made of?";

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

    @Autowired
    private RestTestClient restClient;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    @Test
    void demoJourneySucceedsEndToEnd() throws Exception {
        plainChat();
        ragWithCitations();
        toolCallWithMcpConfirmation();
        agenticWorkflow();
    }

    /** demo.sh §1: a conversation with no {@code ragProfile} streams tokens back over SSE. */
    private void plainChat() throws Exception {
        UUID conversationId = createConversation(null);

        HttpResponse<String> response = postMessage(conversationId, "hello");

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:token");
        assertThat(response.body()).contains("event:usage");
        assertThat(response.body()).contains("event:done");
    }

    /** demo.sh §2: upload a document through the real async pipeline, wait for INDEXED, then ask a
     * RAG-profile conversation the question it answers and check for a real citation event. */
    private void ragWithCitations() throws Exception {
        String jobLocation = uploadAndAwaitIndexed(RAG_DOC_TEXT, "demo-doc");
        assertThat(jobLocation).isNotNull();

        UUID conversationId = createConversation("dense-only");
        HttpResponse<String> response = postMessage(conversationId, RAG_DOC_TEXT);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("event:citation");
        assertThat(response.body()).contains("event:done");
    }

    /** demo.sh §3: {@code GET /api/v1/tools} lists an {@code mcp:self:*} tool discovered over a real
     * MCP handshake (this app connecting to its own {@code /mcp} server); calling it pauses the
     * stream for confirmation before the result comes back. */
    private void toolCallWithMcpConfirmation() throws Exception {
        awaitMcpClientToolRegistered();

        UUID conversationId = createConversation(null);
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

            String body = String.join("\n", linesFuture.get(10, TimeUnit.SECONDS));
            assertThat(body).contains("event:tool_call_pending");
            assertThat(body).contains("\"toolName\":\"mcp:self:calculator\"");
            assertThat(body).contains("event:tool_result");
            assertThat(body).contains("event:done");
        } finally {
            reader.shutdownNow();
        }
    }

    /** demo.sh §4: two more documents seed the workflow's two sub-queries, then the six-stage
     * documentation-research workflow runs to completion with a cited answer. */
    private void agenticWorkflow() throws Exception {
        assertThat(uploadAndAwaitIndexed(WORKFLOW_SUB_QUERY_A, "demo-subquery-a"))
                .isNotNull();
        assertThat(uploadAndAwaitIndexed(WORKFLOW_SUB_QUERY_B, "demo-subquery-b"))
                .isNotNull();

        WorkflowRunResponse started = restClient
                .post()
                .uri("/api/v1/workflows/documentation-research/runs")
                .body(new WorkflowRunRequest(WORKFLOW_QUERY))
                .exchange()
                .expectStatus()
                .isEqualTo(202)
                .expectBody(WorkflowRunResponse.class)
                .returnResult()
                .getResponseBody();

        WorkflowRunResponse completed = awaitTerminalRun(started.id());

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.steps())
                .extracting(WorkflowStepResponse::name)
                .containsExactly(
                        "plan-sub-queries", "retrieve", "extract-per-source", "synthesise", "self-check", "answer");
        assertThat(completed.steps())
                .allSatisfy(step -> assertThat(step.status()).isEqualTo("SUCCEEDED"));
    }

    private String uploadAndAwaitIndexed(String content, String title) throws Exception {
        HttpResponse<String> uploadResponse =
                IngestionTestHttp.upload(httpClient, PORT, title + ".md", "text/markdown", content, title);
        assertThat(uploadResponse.statusCode()).isEqualTo(202);
        String jobLocation = uploadResponse.headers().firstValue("Location").orElseThrow();

        String finalJobBody = IngestionTestHttp.awaitTerminalJob(httpClient, PORT, jobLocation);
        assertThat(finalJobBody).contains("\"stage\":\"INDEXED\"");
        return jobLocation;
    }

    private void awaitMcpClientToolRegistered() {
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

    private UUID createConversation(String ragProfile) {
        return restClient
                .post()
                .uri("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateConversationRequest(ragProfile))
                .exchange()
                .expectStatus()
                .isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    private WorkflowRunResponse awaitTerminalRun(UUID id) {
        var holder = new Object() {
            WorkflowRunResponse response;
        };
        await().atMost(Duration.ofSeconds(30))
                .pollInterval(Duration.ofMillis(200))
                .until(() -> {
                    holder.response = restClient
                            .get()
                            .uri("/api/v1/workflows/runs/" + id)
                            .exchange()
                            .expectStatus()
                            .isOk()
                            .expectBody(WorkflowRunResponse.class)
                            .returnResult()
                            .getResponseBody();
                    return !holder.response.status().equals("PENDING")
                            && !holder.response.status().equals("RUNNING");
                });
        return holder.response;
    }

    private HttpResponse<String> postMessage(UUID conversationId, String content) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create("http://localhost:%d/api/v1/conversations/%s/messages".formatted(PORT, conversationId)))
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"%s\"}".formatted(content)))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private List<String> readSseLines(UUID conversationId, String content, CompletableFuture<UUID> callIdFuture) {
        List<String> collected = new ArrayList<>();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://localhost:%d/api/v1/conversations/%s/messages".formatted(PORT, conversationId)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"%s\"}".formatted(content)))
                    .build();
            HttpResponse<Stream<String>> response = httpClient.send(request, HttpResponse.BodyHandlers.ofLines());

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
