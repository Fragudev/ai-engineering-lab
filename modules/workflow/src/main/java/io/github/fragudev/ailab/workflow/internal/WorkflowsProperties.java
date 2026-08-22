package io.github.fragudev.ailab.workflow.internal;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param maxSubQueries caps {@code plan-sub-queries}' fan-out; a reasoned starting bound, not
 *     measured against any dataset (AGENTS.md rule 2), same honesty as {@code
 *     ai.tools.max-calls-per-turn}
 * @param maxSourcesToExtract caps {@code extract-per-source}'s fan-out, after deduplicating
 *     retrieved chunks by id across every sub-query
 * @param maxLlmCallsPerRun the real, enforced T5 (denial of wallet) bound docs/threat-model.md
 *     names as planned — scoped per engine invocation, not cumulative across a restart+resume
 * @param stageRetryAttempts additional attempts after the first, before a stage is compensated
 * @param stepTimeout bounds one individual retrieval or LLM call inside a stage's fan-out
 */
@ConfigurationProperties(prefix = "ai.workflow")
public record WorkflowsProperties(
        boolean enabled,
        int maxSubQueries,
        int maxSourcesToExtract,
        int maxLlmCallsPerRun,
        int stageRetryAttempts,
        Duration stepTimeout) {}
