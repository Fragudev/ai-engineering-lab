package io.github.fragudev.ailab.aiprovider.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Post-roadmap review B3 (issue #27): {@code LmStudioProperties} carried zero constraints — Phase
 * 8's own latency work required overriding {@code AI_PROVIDER_LMSTUDIO_TIMEOUT}/{@code
 * ..._CHAT_MODEL}/{@code ..._EMBEDDING_MODEL} by hand, and a typo in any of them would have failed
 * obscurely rather than refusing to start. {@link LmStudioProviderConfiguration}'s own beans are
 * gated behind {@code @Profile("lmstudio")}, which {@link ApplicationContextRunner} never
 * activates, so this exercises only the property binding/validation, not the OpenAI client wiring.
 */
class LmStudioPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(LmStudioProviderConfiguration.class);

    @Test
    void startsWithValidProperties() {
        validPropertyValues().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithABlankChatModel() {
        validPropertyValues()
                .withPropertyValues("ai.provider.lmstudio.chat-model=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The field name is inside the wrapped BindValidationException, not the outer
                    // ConfigurationPropertiesBindException's own top-level message.
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("chatModel");
                });
    }

    private ApplicationContextRunner validPropertyValues() {
        return contextRunner.withPropertyValues(
                "ai.provider.lmstudio.base-url=http://localhost:1234/v1",
                "ai.provider.lmstudio.chat-model=local-model",
                "ai.provider.lmstudio.embedding-model=bge-m3",
                "ai.provider.lmstudio.timeout=60s");
    }
}
