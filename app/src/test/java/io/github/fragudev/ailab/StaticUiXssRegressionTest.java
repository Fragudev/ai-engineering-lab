package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

/**
 * Regression guard for issue #21 (stored XSS in the document list): the shipped chat UI script
 * must never write untrusted values through {@code innerHTML} again. Reintroducing it — even for
 * an unrelated feature — would recreate the same injection path {@link SecurityHeadersFilter}'s
 * strict {@code script-src}/{@code style-src} and {@link DocumentXssRegressionTest} guard against
 * from the other two directions.
 */
class StaticUiXssRegressionTest {

    @Test
    void appJsNeverUsesInnerHtml() throws IOException {
        assertThat(readClasspathResource("static/app.js")).doesNotContain("innerHTML");
    }

    private static String readClasspathResource(String path) throws IOException {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
