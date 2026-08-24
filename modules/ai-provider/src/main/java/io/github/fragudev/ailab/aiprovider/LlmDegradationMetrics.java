package io.github.fragudev.ailab.aiprovider;

import io.github.fragudev.ailab.shared.ProviderTimeoutException;
import io.github.fragudev.ailab.shared.ProviderUnavailableException;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * {@code llm_degradation_total{component,reason}} (docs/architecture.md #12), mirroring {@code
 * tools.internal.ToolMetrics}'s exact shape. Post-roadmap review issue #37: during Phase 8's live
 * evaluation run, {@code LlmReranker} fell back to fused order on every single call against a real
 * 27B model — a complete, silent failure of a headline feature whose only signal was a {@code WARN}
 * log line. A degraded {@link DegradingChatCall} call now always increments this counter, so that
 * kind of failure shows up as a flat line at 100% instead of requiring someone to be reading logs.
 */
@Component
public class LlmDegradationMetrics {

    private final MeterRegistry registry;

    public LlmDegradationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** The provider call itself threw — {@code reason} is {@code timeout} or
     * {@code provider-unavailable} for the two typed cases this project's provider adapters raise
     * (docs/adr/0004-ai-provider-abstraction.md), or {@code provider-error} for anything else. */
    public void recordProviderFailure(String component, RuntimeException exception) {
        registry.counter("llm_degradation_total", "component", component, "reason", reasonFor(exception))
                .increment();
    }

    /** The provider call succeeded but the response was unusable — empty, or failed a caller-specific
     * structural check ({@link DegradingChatCall}'s {@code parse} returning {@code null}). */
    public void recordParseFailure(String component) {
        registry.counter("llm_degradation_total", "component", component, "reason", "parse-failure")
                .increment();
    }

    private static String reasonFor(RuntimeException exception) {
        if (exception instanceof ProviderTimeoutException) {
            return "timeout";
        }
        if (exception instanceof ProviderUnavailableException) {
            return "provider-unavailable";
        }
        return "provider-error";
    }
}
