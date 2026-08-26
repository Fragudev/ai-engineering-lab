package io.github.fragudev.ailab.aiprovider.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.ai.openai.AbstractOpenAiOptions;

/**
 * Post-roadmap review issue #65: {@code ai.provider.lmstudio.timeout} governed nothing. Every call
 * died at exactly 60.5s whether the property said 15s, 300s or 600s, because Spring AI's own
 * {@link AbstractOpenAiOptions#DEFAULT_TIMEOUT} (60 seconds) applies unless the options object
 * carries a timeout — and this configuration never set one.
 *
 * <p>These tests pin the value reaching the options object, which is what Spring AI threads through
 * {@code OpenAiSetup} into the HTTP client that actually governs the call. That is deliberately a
 * different assertion from "a timeout exists somewhere": the previous attempt at this bug (issue
 * #29) set a timeout on a builder that was then discarded, looked correct in review, and was
 * recorded as fixed on the strength of a live run "lasting longer" — which was never evidence.
 */
class LmStudioProviderConfigurationTest {

    private static LmStudioProperties propertiesWithTimeout(Duration timeout) {
        return new LmStudioProperties("http://localhost:1234/v1", "chat-model", "embedding-model", timeout);
    }

    @Test
    void chatOptionsCarryTheConfiguredTimeout() {
        Duration configured = Duration.ofSeconds(600);

        var options = LmStudioProviderConfiguration.chatOptions(propertiesWithTimeout(configured));

        assertThat(options.getTimeout()).isEqualTo(configured);
    }

    @Test
    void embeddingOptionsCarryTheConfiguredTimeout() {
        Duration configured = Duration.ofSeconds(600);

        var options = LmStudioProviderConfiguration.embeddingOptions(propertiesWithTimeout(configured));

        assertThat(options.getTimeout()).isEqualTo(configured);
    }

    /**
     * The regression guard with teeth. Every value below is one the property was actually set to
     * while reproducing #65 — and every one of them produced a 60.5s failure before the fix, because
     * the options silently fell back to the 60s default. Asserting "not the default" is what
     * distinguishes a timeout that is configured from one that merely happens to exist.
     */
    @Test
    void aConfiguredTimeoutIsNeverSilentlyReplacedByTheSixtySecondDefault() {
        for (Duration configured :
                new Duration[] {Duration.ofSeconds(15), Duration.ofSeconds(300), Duration.ofMinutes(10)}) {
            var chat = LmStudioProviderConfiguration.chatOptions(propertiesWithTimeout(configured));
            var embedding = LmStudioProviderConfiguration.embeddingOptions(propertiesWithTimeout(configured));

            assertThat(chat.getTimeout()).isEqualTo(configured).isNotEqualTo(AbstractOpenAiOptions.DEFAULT_TIMEOUT);
            assertThat(embedding.getTimeout())
                    .isEqualTo(configured)
                    .isNotEqualTo(AbstractOpenAiOptions.DEFAULT_TIMEOUT);
        }
    }

    /** Pins the constant this bug hinged on: if a future Spring AI upgrade changes it, the comments
     * and the issue's own numbers stop matching reality, and this fails rather than drifting. */
    @Test
    void springAisOwnDefaultIsStillTheSixtySecondsThisBugWasAbout() {
        assertThat(AbstractOpenAiOptions.DEFAULT_TIMEOUT).isEqualTo(Duration.ofSeconds(60));
    }
}
