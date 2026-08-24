package io.github.fragudev.ailab.workflow.internal;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/**
 * Post-roadmap review B3 (issue #27): {@code WorkflowsProperties} carried zero constraints — a
 * negative {@code stage-retry-attempts} used to reach {@link StageRunner} and throw {@code
 * NullPointerException} deep inside a workflow run (post-roadmap review B2, issue #26) instead of
 * failing at startup with a readable message. This is the exact scenario the issue's own "How to
 * verify" section names.
 */
class WorkflowsPropertiesValidationTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withUserConfiguration(WorkflowsConfiguration.class);

    @Test
    void startsWithValidProperties() {
        validPropertyValues().run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void refusesToStartWithANegativeStageRetryAttempts() {
        validPropertyValues()
                .withPropertyValues("ai.workflow.stage-retry-attempts=-1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    // The field name is inside the wrapped BindValidationException, not the outer
                    // ConfigurationPropertiesBindException's own top-level message.
                    assertThat(context.getStartupFailure()).hasStackTraceContaining("stageRetryAttempts");
                });
    }

    private ApplicationContextRunner validPropertyValues() {
        return contextRunner.withPropertyValues(
                "ai.workflow.enabled=true",
                "ai.workflow.max-sub-queries=4",
                "ai.workflow.max-sources-to-extract=8",
                "ai.workflow.max-llm-calls-per-run=20",
                "ai.workflow.stage-retry-attempts=2",
                "ai.workflow.step-timeout=30s",
                "ai.workflow.retry-base-delay=500ms");
    }
}
