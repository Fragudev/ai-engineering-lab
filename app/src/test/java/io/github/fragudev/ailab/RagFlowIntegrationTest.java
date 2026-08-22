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
 * Drives a RAG-augmented conversation turn end to end under the {@code recorded} profile — no
 * Kafka/live model, deterministic (docs/architecture.md #8). Chunks are seeded directly through
 * {@link ChunkService} rather than the full async ingestion pipeline (already covered by
 * {@code IngestionFlowIntegrationTest}), with a parent {@link IngestionService#upload} call first
 * since {@code chunk.document_id} is a real FK.
 *
 * <p>{@link io.github.fragudev.ailab.aiprovider.internal.RecordedEmbeddingProvider} is
 * hash-seeded per exact input string, so a chunk whose content is the literal query text embeds
 * identically to the query at retrieval time (cosine distance 0) — a deliberate, deterministic way
 * to guarantee a "found it" match without depending on real semantic similarity, which recorded
 * fixtures can't provide.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RagFlowIntegrationTest {

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
    void answersWithCitationsWhenRelevantChunksExist() throws Exception {
        String query = "What does consumer lag measure?";
        UploadOutcome outcome = ingestionService.upload(
                "kafka-monitoring.md",
                "text/markdown",
                "seed document for RagFlowIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk relevantChunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                query,
                8,
                null,
                embeddingProvider.embed(List.of(query)).get(0).vector());
        Chunk otherChunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                1,
                "Kafka partitions distribute load across brokers.",
                8,
                null,
                embeddingProvider
                        .embed(List.of("Kafka partitions distribute load across brokers."))
                        .get(0)
                        .vector());
        chunkService.saveAll(List.of(relevantChunk, otherChunk));

        UUID conversationId = createConversation("dense-only");

        HttpResponse<String> streamResponse = postMessage(conversationId, query);

        assertThat(streamResponse.statusCode()).isEqualTo(200);
        assertThat(streamResponse.body()).contains("event:token");
        assertThat(streamResponse.body()).contains("event:citation");
        assertThat(streamResponse.body()).doesNotContain("[1]").doesNotContain("[2]");
        assertThat(streamResponse.body()).contains("event:usage");
        assertThat(streamResponse.body()).contains("event:done");

        List<MessageResponse> messages = getMessages(conversationId);
        assertThat(messages).hasSize(2);
        MessageResponse assistantMessage = messages.get(1);
        assertThat(assistantMessage.content()).contains("Consumer lag measures");
        assertThat(assistantMessage.content()).doesNotContain("[1]").doesNotContain("[2]");
        assertThat(assistantMessage.citations()).hasSize(2);
        assertThat(assistantMessage.citations().get(0).chunkId()).isEqualTo(relevantChunk.id());
    }

    @Test
    void declinesToAnswerWhenNoRelevantContextExists() throws Exception {
        UUID conversationId = createConversation("dense-only");

        HttpResponse<String> streamResponse = postMessage(conversationId, "What is the boiling point of mercury?");

        assertThat(streamResponse.statusCode()).isEqualTo(200);
        assertThat(streamResponse.body()).contains("doesn't contain enough information");
        assertThat(streamResponse.body()).doesNotContain("event:citation");

        List<MessageResponse> messages = getMessages(conversationId);
        MessageResponse assistantMessage = messages.get(1);
        assertThat(assistantMessage.content()).contains("doesn't contain enough information");
        assertThat(assistantMessage.citations()).isEmpty();
    }

    private UUID createConversation(String ragProfile) {
        return restClient
                .post()
                .uri("/api/v1/conversations")
                .body(new CreateConversationRequest(ragProfile))
                .exchange()
                .expectStatus()
                .isEqualTo(201)
                .expectBody(ConversationResponse.class)
                .returnResult()
                .getResponseBody()
                .id();
    }

    private List<MessageResponse> getMessages(UUID conversationId) {
        return restClient
                .get()
                .uri("/api/v1/conversations/" + conversationId + "/messages")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(new ParameterizedTypeReference<List<MessageResponse>>() {})
                .returnResult()
                .getResponseBody();
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
