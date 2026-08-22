package io.github.fragudev.ailab;

import io.github.fragudev.ailab.tools.Tool;
import io.github.fragudev.ailab.tools.ToolDefinition;
import io.github.fragudev.ailab.tools.ToolExecutionContext;
import io.github.fragudev.ailab.tools.ToolResult;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * {@code POST /api/v1/tools/{name}:invoke} — the direct, out-of-band tool path, real for all four
 * roadmap acceptance criteria: invalid arguments (400), scope denial (403), timeout (504), and the
 * happy path (200). {@code ai.tools.granted-scopes} is overridden to exclude
 * {@code knowledge-base:search} (calculator stays granted, so the happy-path test has a real
 * success to check) so the 403 case has something real to deny; a slow test-only {@link Tool} bean
 * ({@code @Profile("tool-invoke-timeout-test")}) proves the 504 case without a real 5s wait.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles({"recorded", "tool-invoke-timeout-test"})
@TestPropertySource(
        properties = {"ai.tools.granted-scopes=calculator:use,external-api:mock", "ai.tools.default-timeout=200ms"})
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class ToolInvokeIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    @TestConfiguration
    @Profile("tool-invoke-timeout-test")
    static class SlowToolConfiguration {

        @Bean
        Tool neverFinishingTool() {
            return new Tool() {
                @Override
                public ToolDefinition definition() {
                    return new ToolDefinition(
                            "never-finishes",
                            "1",
                            "never completes",
                            "{}",
                            "{}",
                            Set.of(),
                            false,
                            Duration.ofMillis(200));
                }

                @Override
                public ToolResult execute(ToolExecutionContext context, String argumentsJson) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return ToolResult.ok("{}");
                }
            };
        }
    }

    @Autowired
    private RestTestClient restClient;

    @Test
    void invokeCalculatorSucceeds() {
        restClient
                .post()
                .uri("/api/v1/tools/calculator:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ToolInvokeRequest(Map.of("expression", "6 * 7")))
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$.success")
                .isEqualTo(true);
    }

    @Test
    void invalidArgumentsAreRejectedWithA400() {
        restClient
                .post()
                .uri("/api/v1/tools/calculator:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ToolInvokeRequest(Map.of()))
                .exchange()
                .expectStatus()
                .isBadRequest();
    }

    @Test
    void unauthorizedToolIsDeniedWithA403BeforeExecution() {
        restClient
                .post()
                .uri("/api/v1/tools/knowledge-base-search:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ToolInvokeRequest(Map.of("query", "anything")))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    void unknownToolIs404() {
        restClient
                .post()
                .uri("/api/v1/tools/does-not-exist:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ToolInvokeRequest(Map.of()))
                .exchange()
                .expectStatus()
                .isNotFound();
    }

    @Test
    void toolExceedingItsTimeoutIsCancelledAndReportedAs504() {
        restClient
                .post()
                .uri("/api/v1/tools/never-finishes:invoke")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new ToolInvokeRequest(Map.of()))
                .exchange()
                .expectStatus()
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    void listToolsIncludesEveryRegisteredTool() {
        restClient
                .get()
                .uri("/api/v1/tools")
                .exchange()
                .expectStatus()
                .isOk()
                .expectBody()
                .jsonPath("$[?(@.name=='calculator')]")
                .exists()
                .jsonPath("$[?(@.name=='mock-weather')]")
                .exists()
                .jsonPath("$[?(@.name=='knowledge-base-search')]")
                .exists();
    }
}
