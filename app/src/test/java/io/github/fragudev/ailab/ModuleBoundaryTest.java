package io.github.fragudev.ailab;

import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;

/**
 * Verifies the module boundaries described in AGENTS.md (Module boundaries) and
 * docs/architecture.md #3: each module's public API is its root package, everything under
 * {@code internal} is off-limits to other modules, and the dependency graph is acyclic.
 *
 * <p>Spring Modulith discovers modules by package under {@link AiEngineeringLabApplication}'s
 * base package across the whole classpath, so this verifies boundaries across the separate Maven
 * modules, not just within this jar.
 */
class ModuleBoundaryTest {

    private final ApplicationModules modules = ApplicationModules.of(AiEngineeringLabApplication.class);

    @Test
    void respectsModuleBoundaries() {
        assertThatCode(modules::verify).doesNotThrowAnyException();
    }
}
