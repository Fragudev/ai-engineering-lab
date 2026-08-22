package io.github.fragudev.ailab.workflow.internal;

import java.math.BigDecimal;
import java.util.Map;

/** What one stage's {@link StageFunction} produces on success — its output, and what it cost. */
record StageOutcome(Map<String, Object> output, BigDecimal costUsd) {

    static StageOutcome of(Map<String, Object> output) {
        return new StageOutcome(output, BigDecimal.ZERO);
    }

    static StageOutcome of(Map<String, Object> output, BigDecimal costUsd) {
        return new StageOutcome(output, costUsd);
    }
}
