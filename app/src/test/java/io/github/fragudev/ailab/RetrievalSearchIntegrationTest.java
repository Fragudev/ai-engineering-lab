package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.ingestion.UploadOutcome;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import java.nio.charset.StandardCharsets;
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
 * {@code POST /api/v1/retrieval:search} across all 4 named profiles — the debug view the roadmap's
 * acceptance criterion asks for: candidates and scores before and after fusion and reranking. Same
 * chunk-seeding trick as {@code RagFlowIntegrationTest} (see its javadoc) for a deterministic match
 * under the {@code recorded} profile.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class RetrievalSearchIntegrationTest {

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

    private static final String QUERY = "What does consumer lag measure?";

    private UUID relevantChunkId;

    @Test
    void showsPerRetrieverAndFusedAndRerankedScoresAcrossProfiles() throws Exception {
        seedChunks();

        assertProfile("dense-only", false);
        assertProfile("hybrid", false);
        assertProfile("hybrid-rerank", true);
        // hybrid-rerank-llm: the recorded chat fixture's default response isn't a parseable ranking,
        // so LlmReranker falls back to fused order — exercising that fallback path for real, not a
        // shortcut around it.
        assertProfile("hybrid-rerank-llm", false);
    }

    @Test
    void rejectsAnUnknownProfile() {
        restClient
                .post()
                .uri("/api/v1/retrieval:search")
                .body(new RetrievalSearchRequest(QUERY, "not-a-real-profile"))
                .exchange()
                .expectStatus()
                .isEqualTo(400);
    }

    private void assertProfile(String profileName, boolean expectRerankScore) {
        RetrievalTraceResponse trace = restClient
                .post()
                .uri("/api/v1/retrieval:search")
                .body(new RetrievalSearchRequest(QUERY, profileName))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody(RetrievalTraceResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(trace.ragProfile()).isEqualTo(profileName);
        assertThat(trace.originalQuery()).isEqualTo(QUERY);
        assertThat(trace.results()).isNotEmpty();

        SearchResultResponse top = trace.results().get(0);
        assertThat(top.finalRank()).isEqualTo(1);
        assertThat(top.chunkId()).isEqualTo(relevantChunkId);
        assertThat(top.vectorDistance()).isNotNull().isCloseTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(top.fusedScore()).isPositive();
        if (expectRerankScore) {
            assertThat(top.rerankScore()).isNotNull();
        } else {
            assertThat(top.rerankScore()).isNull();
        }
    }

    private void seedChunks() throws Exception {
        UploadOutcome outcome = ingestionService.upload(
                "kafka-monitoring.md",
                "text/markdown",
                "seed document for RetrievalSearchIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk relevantChunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                QUERY,
                8,
                null,
                embeddingProvider.embed(List.of(QUERY)).get(0).vector());
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
        relevantChunkId = relevantChunk.id();
    }
}
