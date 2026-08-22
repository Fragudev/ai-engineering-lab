package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.ingestion.UploadOutcome;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives the documentation-research workflow end to end under the {@code recorded} profile —
 * plan-sub-queries → retrieve → extract-per-source → synthesise → self-check → answer, all six
 * stages persisted with real, inspectable input/output/attempts/cost (docs/roadmap.md Phase 6
 * acceptance criterion 2). Same chunk-seeding trick as {@code RagFlowIntegrationTest} (see its
 * javadoc): each sub-query is seeded as its own chunk's exact content, so
 * {@code RecordedEmbeddingProvider}'s hash-seeded embeddings guarantee retrieval finds it
 * deterministically.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WorkflowRunIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ChunkService chunkService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    private static final String QUERY = "What is the boiling point of water and what is dry ice made of?";
    private static final String SUB_QUERY_A = "What is the boiling point of water?";
    private static final String SUB_QUERY_B = "What is dry ice made of?";

    @Test
    void runsAllSixStagesAndProducesACitedAnswer() throws Exception {
        seedChunks();

        WorkflowRunResponse started = restClient
                .post()
                .uri("/api/v1/workflows/documentation-research/runs")
                .body(new WorkflowRunRequest(QUERY))
                .exchange()
                .expectStatus()
                .isEqualTo(202)
                .expectHeader()
                .exists("Location")
                .expectBody(WorkflowRunResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(started).isNotNull();
        assertThat(started.status()).isIn("PENDING", "RUNNING");

        WorkflowRunResponse completed = awaitTerminalRun(started.id());

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        assertThat(completed.steps())
                .extracting(WorkflowStepResponse::name)
                .containsExactly(
                        "plan-sub-queries", "retrieve", "extract-per-source", "synthesise", "self-check", "answer");
        assertThat(completed.steps())
                .allSatisfy(step -> assertThat(step.status()).isEqualTo("SUCCEEDED"))
                .allSatisfy(step -> assertThat(step.attempts()).isGreaterThanOrEqualTo(1));

        String answer = (String) completed.output().get("answer");
        assertThat(answer).contains("[1]").contains("[2]");
        assertThat(completed.output()).containsKey("totalCostUsd");
    }

    @Test
    void rejectsAnUnknownWorkflowType() {
        restClient
                .post()
                .uri("/api/v1/workflows/not-a-real-type/runs")
                .body(new WorkflowRunRequest(QUERY))
                .exchange()
                .expectStatus()
                .isEqualTo(400);
    }

    @Test
    void unknownRunIdIs404() {
        restClient
                .get()
                .uri("/api/v1/workflows/runs/" + UUID.randomUUID())
                .exchange()
                .expectStatus()
                .isEqualTo(404);
    }

    private WorkflowRunResponse awaitTerminalRun(UUID id) {
        var holder = new Object() {
            WorkflowRunResponse response;
        };
        await().atMost(Duration.ofSeconds(15))
                .pollInterval(Duration.ofMillis(100))
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

    private void seedChunks() throws Exception {
        UploadOutcome outcome = ingestionService.upload(
                "workflow-seed.md",
                "text/markdown",
                "seed document for WorkflowRunIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk chunkA = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                SUB_QUERY_A,
                6,
                null,
                embeddingProvider.embed(List.of(SUB_QUERY_A)).get(0).vector());
        Chunk chunkB = new Chunk(
                UUID.randomUUID(),
                documentId,
                1,
                SUB_QUERY_B,
                6,
                null,
                embeddingProvider.embed(List.of(SUB_QUERY_B)).get(0).vector());
        chunkService.saveAll(List.of(chunkA, chunkB));
    }
}
