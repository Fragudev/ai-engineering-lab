package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.ingestion.UploadOutcome;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The flagship proof of docs/threat-model.md T2's confirmation gate: a RAG-context turn's tool call
 * pauses the SSE stream, a separate {@code POST /api/v1/tool-calls/{callId}:confirm} resolves it,
 * and the SAME stream resumes and completes. A raw {@link HttpClient} reads the SSE response on a
 * background thread (blocking between lines, via {@code BodyHandlers.ofLines()}) while the main
 * thread posts the confirmation concurrently — this is why a raw client is used here instead of
 * {@code RestTestClient}, same reasoning as {@code ConversationFlowIntegrationTest}'s javadoc, plus
 * the genuine need for a second, concurrent in-flight request this time.
 *
 * <p>Seeds a chunk via {@link ChunkService} directly (not the async Kafka pipeline, already covered
 * by {@code IngestionFlowIntegrationTest}) whose content is the literal query text, so
 * {@code RecordedEmbeddingProvider}'s hash-seeded embeddings guarantee a deterministic retrieval
 * match — same technique as {@code RagFlowIntegrationTest}. The fixture whose {@code matchContains}
 * is a substring of that same query text is the {@code mock-weather} tool-call fixture
 * (fixtures/chat/fixtures.json), so the RAG turn's model response is a tool call — and because
 * {@code RagPipeline} always passes {@link io.github.fragudev.ailab.tools.ToolCallOrigin#RAG_CONTEXT},
 * that first call is gated regardless of which tool it is.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@TestPropertySource(properties = "ai.tools.confirmation-timeout=3s")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ConversationToolConfirmationIntegrationTest {

    private static final String QUERY = "what's the weather in paris";
    private static final Pattern CALL_ID_PATTERN = Pattern.compile("\"callId\":\"([0-9a-fA-F-]{36})\"");

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

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ChunkService chunkService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Test
    void confirmingResumesTheStreamAndCompletesTheTurn() throws Exception {
        UUID conversationId = seedChunkAndCreateRagConversation();

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<UUID> callIdFuture = new CompletableFuture<>();
            var linesFuture = CompletableFuture.supplyAsync(() -> readSseLines(conversationId, callIdFuture), reader);

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
            assertThat(body).contains("event:tool_result");
            assertThat(body).contains("event:usage");
            assertThat(body).contains("event:done");
        } finally {
            reader.shutdownNow();
        }
    }

    @Test
    void unconfirmedCallStillResolvesWithoutHangingTheConversation() throws Exception {
        UUID conversationId = seedChunkAndCreateRagConversation();

        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<UUID> callIdFuture = new CompletableFuture<>();
            var linesFuture = CompletableFuture.supplyAsync(() -> readSseLines(conversationId, callIdFuture), reader);

            callIdFuture.get(10, TimeUnit.SECONDS); // confirms the pending event fired; never confirmed

            List<String> allLines = linesFuture.get(10, TimeUnit.SECONDS);
            String body = String.join("\n", allLines);

            assertThat(body).contains("event:tool_call_pending");
            assertThat(body).contains("\"outcome\":\"TIMEOUT\"");
            assertThat(body).contains("event:done");
        } finally {
            reader.shutdownNow();
        }
    }

    private UUID seedChunkAndCreateRagConversation() throws Exception {
        UploadOutcome outcome = ingestionService.upload(
                "confirmation-test-doc",
                "text/markdown",
                "seed document for ConversationToolConfirmationIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk chunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                QUERY,
                8,
                null,
                embeddingProvider.embed(List.of(QUERY)).get(0).vector());
        chunkService.saveAll(List.of(chunk));

        return restClient
                .post()
                .uri("/api/v1/conversations")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new CreateConversationRequest("dense-only"))
                .exchange()
                .expectStatus()
                .isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    /** Reads the SSE response line by line, completing {@code callIdFuture} the moment a
     * {@code tool_call_pending} event's data line is seen, then keeps reading until the stream
     * closes — resuming (if confirmed) on whatever thread the confirm request lands on. */
    private List<String> readSseLines(UUID conversationId, CompletableFuture<UUID> callIdFuture) {
        List<String> collected = new ArrayList<>();
        try {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(
                            "http://localhost:%d/api/v1/conversations/%s/messages".formatted(port, conversationId)))
                    .header("Content-Type", "application/json")
                    .header("Accept", "text/event-stream")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"content\":\"%s\"}".formatted(QUERY)))
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
