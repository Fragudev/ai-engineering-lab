package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.aiprovider.EmbeddingProvider;
import io.github.fragudev.ailab.evaluation.EvalReport;
import io.github.fragudev.ailab.evaluation.EvalRunConfig;
import io.github.fragudev.ailab.evaluation.EvalRunner;
import io.github.fragudev.ailab.evaluation.ProfileSummary;
import io.github.fragudev.ailab.ingestion.IngestionService;
import io.github.fragudev.ailab.ingestion.UploadOutcome;
import io.github.fragudev.ailab.knowledge.Chunk;
import io.github.fragudev.ailab.knowledge.ChunkService;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Drives {@link EvalRunner} end to end under the {@code recorded} profile against a small inline
 * dataset — real retrieval, real citation extraction, real metric computation, no mocking (same
 * discipline as {@code RagFlowIntegrationTest}). Seeds one chunk directly through
 * {@link ChunkService} rather than the async Kafka pipeline (already covered by
 * {@code IngestionFlowIntegrationTest}); the chunk's content is the literal query text so
 * {@link io.github.fragudev.ailab.aiprovider.internal.RecordedEmbeddingProvider}'s hash-seeded
 * embeddings guarantee a deterministic "found it" match, same technique as {@code RagFlowIntegrationTest}.
 */
@Testcontainers
@ActiveProfiles("recorded")
@SpringBootTest
class EvaluationFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @Autowired
    private IngestionService ingestionService;

    @Autowired
    private ChunkService chunkService;

    @Autowired
    private EmbeddingProvider embeddingProvider;

    @Autowired
    private EvalRunner evalRunner;

    @TempDir
    private Path tempDir;

    @Test
    void runsDatasetAgainstRealPipelineAndWritesReport() throws IOException {
        String answerableQuestion = "What does consumer lag measure in Kafka?";
        String unanswerableQuestion = "What is the airspeed velocity of an unladen swallow?";

        UploadOutcome outcome = ingestionService.upload(
                "eval-fixture",
                "text/markdown",
                "seed document for EvaluationFlowIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk relevantChunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                answerableQuestion,
                8,
                null,
                embeddingProvider.embed(List.of(answerableQuestion)).get(0).vector());
        chunkService.saveAll(List.of(relevantChunk));

        Path datasetPath = tempDir.resolve("inline.yaml");
        Files.writeString(datasetPath, """
                name: eval-flow-integration-test
                version: "1"
                cases:
                  - key: answerable
                    question: "%s"
                    expectedAnswer: "Consumer lag measures how far behind a consumer is."
                    goldChunkRefs: ["eval-fixture#0"]
                    tags: [test]
                    category: FACTUAL_SINGLE_HOP
                  - key: unanswerable
                    question: "%s"
                    expectedAnswer: "The knowledge base doesn't contain enough information to answer this question."
                    goldChunkRefs: []
                    tags: [test]
                    category: UNANSWERABLE
                """.formatted(answerableQuestion, unanswerableQuestion));

        EvalRunConfig config = new EvalRunConfig(datasetPath, List.of("dense-only"), 1, false, null);
        Path reportPath = evalRunner.runAndWriteReport(config, tempDir, true);

        assertThat(reportPath).exists();
        String markdown = Files.readString(reportPath);
        assertThat(markdown).contains("dense-only");
        assertThat(markdown).contains("Methodology limitations");
        assertThat(markdown).contains("recorded");

        Path jsonPath = Path.of(reportPath.toString().replace(".md", ".json"));
        assertThat(jsonPath).exists();
        String json = Files.readString(jsonPath);
        assertThat(json).contains("\"ragProfile\" : \"dense-only\"");
        assertThat(json).doesNotContain("NaN");
    }

    @Test
    void runReturnsAccurateMetricsForARealAnswerableAndUnanswerableCase() throws IOException {
        String answerableQuestion = "How does replication factor affect Kafka durability?";
        String unanswerableQuestion = "What is the boiling point of mercury?";

        UploadOutcome outcome = ingestionService.upload(
                "eval-fixture-2",
                "text/markdown",
                "second seed document for EvaluationFlowIntegrationTest".getBytes(StandardCharsets.UTF_8));
        UUID documentId = outcome.document().id().value();

        Chunk relevantChunk = new Chunk(
                UUID.randomUUID(),
                documentId,
                0,
                answerableQuestion,
                8,
                null,
                embeddingProvider.embed(List.of(answerableQuestion)).get(0).vector());
        chunkService.saveAll(List.of(relevantChunk));

        Path datasetPath = tempDir.resolve("inline2.yaml");
        Files.writeString(datasetPath, """
                name: eval-flow-integration-test-2
                version: "1"
                cases:
                  - key: answerable
                    question: "%s"
                    expectedAnswer: "Higher replication factor improves durability."
                    goldChunkRefs: ["eval-fixture-2#0"]
                    tags: [test]
                    category: FACTUAL_SINGLE_HOP
                  - key: unanswerable
                    question: "%s"
                    expectedAnswer: "The knowledge base doesn't contain enough information to answer this question."
                    goldChunkRefs: []
                    tags: [test]
                    category: UNANSWERABLE
                """.formatted(answerableQuestion, unanswerableQuestion));

        EvalRunConfig config = new EvalRunConfig(datasetPath, List.of("dense-only"), 1, false, null);
        EvalReport report = evalRunner.run(config);

        assertThat(report.profiles()).hasSize(1);
        ProfileSummary summary = report.profiles().get(0);
        assertThat(summary.ragProfile()).isEqualTo("dense-only");
        assertThat(summary.recallAtK().mean()).isEqualTo(1.0);
        // Both dataset cases ran to completion under the recorded profile — nothing skipped, so the
        // numbers above cover the whole dataset (the guard added for issues #65/#67).
        assertThat(summary.coverage().complete()).isTrue();
        assertThat(summary.coverage().completed()).isEqualTo(2);
        // Under the `recorded` profile the deterministic gate genuinely does fire for the
        // unanswerable case: RecordedEmbeddingProvider is hash-seeded per exact string, so a question
        // matching no seeded chunk lands far past maxVectorDistance. This is the gate half of
        // declining, and it stays exact (post-roadmap review issue #61).
        assertThat(summary.gateAbstentionRate().mean()).isEqualTo(1.0);
        // The other half is judge-scored, and this run sets runJudge=false — so it must read as
        // *not measured* (NaN -> "n/a" in the report), never as 0.0, which would claim the turn
        // declined incorrectly.
        assertThat(summary.refusalCorrectness().mean()).isNaN();
    }
}
