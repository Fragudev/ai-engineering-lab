package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.fragudev.ailab.ingestion.IngestionService;
import java.nio.charset.StandardCharsets;
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
 * Regression coverage for issue #21 (stored XSS in the document list). The app deliberately does
 * not sanitize a document's title server-side — {@code DocumentController.upload} stores it
 * verbatim, same as before this fix. What changed is the two things that make an untrusted title
 * inert: {@link StaticUiXssRegressionTest} pins the client rendering with {@code textContent}
 * instead of {@code innerHTML}, and this test pins the {@code Content-Security-Policy} header
 * ({@link SecurityHeadersFilter}) that backstops it — a strict {@code script-src}/{@code
 * style-src} with no {@code unsafe-inline} means even a reintroduced {@code innerHTML} bug could
 * not execute an injected {@code <script>} or inline event handler in a browser that honours it.
 */
@Testcontainers
@AutoConfigureRestTestClient
@ActiveProfiles("recorded")
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
class DocumentXssRegressionTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
                    DockerImageName.parse("pgvector/pgvector:pg17").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_lab")
            .withUsername("ai_lab")
            .withPassword("ai_lab");

    private static final String HOSTILE_TITLE = "<img src=x onerror=alert(1)>";

    @Autowired
    private RestTestClient restClient;

    @Autowired
    private IngestionService ingestionService;

    @Test
    void hostileTitleRoundTripsUnmangledBehindAStrictCspHeader() {
        ingestionService.upload(HOSTILE_TITLE, "text/markdown", "content".getBytes(StandardCharsets.UTF_8));

        String body = restClient
                .get()
                .uri("/api/v1/documents")
                .exchange()
                .expectStatus()
                .isOk()
                .expectHeader()
                .value("Content-Security-Policy", csp -> assertThat(csp)
                        .contains("script-src 'self'")
                        .contains("style-src 'self'")
                        .doesNotContain("unsafe-inline"))
                .expectBody(String.class)
                .returnResult()
                .getResponseBody();

        assertThat(body).contains(HOSTILE_TITLE);
    }
}
