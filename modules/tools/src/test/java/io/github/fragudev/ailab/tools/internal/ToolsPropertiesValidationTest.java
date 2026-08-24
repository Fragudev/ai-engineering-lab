package io.github.fragudev.ailab.tools.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Post-roadmap review B3 (issue #27): {@code ToolsProperties} carried zero constraints, so a typo
 * or a bad environment override surfaced as a confusing failure deep inside a tool call instead of
 * a clear refusal to start. {@link ApplicationContextRunner} boots only {@link ToolsConfiguration}
 * — not the full app — so this asserts the binding/validation failure directly, without
 * Testcontainers or a database.
 */
class ToolsPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(ToolsConfiguration.class);

    @Test
    void startsWithValidProperties() {
        validPropertyValues().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithANonPositiveMaxCallsPerTurn() {
        validPropertyValues()
                .withPropertyValues("ai.tools.max-calls-per-turn=0")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The field name is inside the wrapped BindValidationException, not the outer
                    // ConfigurationPropertiesBindException's own top-level message.
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("maxCallsPerTurn");
                });
    }

    private ApplicationContextRunner validPropertyValues() {
        return contextRunner.withPropertyValues(
                "ai.tools.enabled=true",
                "ai.tools.granted-scopes=calculator:use",
                "ai.tools.default-timeout=5s",
                "ai.tools.confirmation-timeout=60s",
                "ai.tools.max-calls-per-turn=3",
                "ai.tools.max-pending-confirmations=100");
    }
}
