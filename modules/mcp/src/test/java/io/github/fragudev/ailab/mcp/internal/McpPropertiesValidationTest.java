package io.github.fragudev.ailab.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Post-roadmap review B3 (issue #27): {@code McpProperties} carried zero constraints, on either
 * itself or its nested {@code Client} record — {@code @Valid} on the {@code client} field is what
 * makes validation actually cascade into {@code Client}'s own constraints, not just check that the
 * field is non-null.
 */
class McpPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(McpConfiguration.class);

    @Test
    void startsWithValidProperties() {
        validPropertyValues().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithABlankRequiredScope() {
        validPropertyValues()
                .withPropertyValues("ai.mcp.client.required-scope=")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The field name is inside the wrapped BindValidationException, not the outer
                    // ConfigurationPropertiesBindException's own top-level message.
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("requiredScope");
                });
    }

    private ApplicationContextRunner validPropertyValues() {
        return contextRunner.withPropertyValues(
                "ai.mcp.client.required-scope=mcp:external", "ai.mcp.client.default-timeout=10s");
    }
}
