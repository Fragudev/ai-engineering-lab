package io.github.fragudev.ailab.workflow.internal;

/** One stage's actual work, run and retried by {@link StageRunner}. */
@FunctionalInterface
interface StageFunction {
    StageOutcome execute() throws Exception;
}
