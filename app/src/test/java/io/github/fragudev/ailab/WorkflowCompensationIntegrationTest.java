package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
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
 * A run whose {@code retrieve} stage finds nothing (an empty knowledge base — nothing seeded in
 * this test's own, otherwise-untouched database) is compensated: the run ends {@code FAILED} with a
 * clear reason, not left {@code RUNNING} or with a fabricated partial answer (docs/roadmap.md Phase 6
 * acceptance criterion 3). Fails on the stage's first attempt, not after exhausting retries — an
 * empty knowledge base is {@code IllegalStateException}, not a {@code ProviderException}, so
 * {@code StageRunner}'s retryable/non-retryable distinction (post-roadmap review B1) correctly
 * doesn't burn retry attempts on a deterministic failure retrying cannot fix.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class WorkflowCompensationIntegrationTest {

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
    void aRetrievalStageThatFindsNothingCompensatesTheRun() {
        WorkflowRunResponse started = restClient
                .post()
                .uri("/api/v1/workflows/documentation-research/runs")
                .body(new WorkflowRunRequest("Nothing in this knowledge base can answer this."))
                .exchange()
                .expectStatus()
                .isEqualTo(202)
                .expectBody(WorkflowRunResponse.class)
                .returnResult()
                .getResponseBody();

        assertThat(started).isNotNull();

        WorkflowRunResponse completed = awaitTerminalRun(started.id());

        assertThat(completed.status()).isEqualTo("FAILED");
        assertThat(completed.output()).containsKey("failedStage");
        assertThat(completed.output().get("failedStage")).isEqualTo("retrieve");
        assertThat(completed.output()).containsKey("reason");

        WorkflowStepResponse retrieveStep = completed.steps().stream()
                .filter(step -> step.name().equals("retrieve"))
                .findFirst()
                .orElseThrow();
        assertThat(retrieveStep.status()).isEqualTo("FAILED");
        assertThat(retrieveStep.attempts()).isEqualTo(1);
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
}
