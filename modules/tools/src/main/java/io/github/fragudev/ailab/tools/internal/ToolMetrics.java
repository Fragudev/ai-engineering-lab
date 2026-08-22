package io.github.fragudev.ailab.tools.internal;

import io.github.fragudev.ailab.tools.ToolCallOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.springframework.stereotype.Component;

/** {@code tool_invocation_total{tool,outcome}} and {@code tool_duration_seconds}
 * (docs/architecture.md #12) — the first metrics this codebase registers by hand rather than
 * relying purely on Spring Boot's/OTel's autoconfigured instrumentation. */
@Component
public class ToolMetrics {

    private final MeterRegistry registry;

    public ToolMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String toolName, ToolCallOutcome outcome, Duration duration) {
        registry.counter("tool_invocation_total", "tool", toolName, "outcome", outcome.name())
                .increment();
        registry.timer("tool_duration_seconds", "tool", toolName, "outcome", outcome.name())
                .record(duration);
    }
}
