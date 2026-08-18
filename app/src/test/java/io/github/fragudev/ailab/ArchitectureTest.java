package io.github.fragudev.ailab;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Structural invariants from docs/architecture.md #3 that are cheap to check explicitly, with a
 * clear failure message, even though the Maven module graph already makes most of them impossible
 * to violate by accident.
 */
class ArchitectureTest {

    private static final String BASE_PACKAGE = "io.github.fragudev.ailab";

    private static final String[] DOMAIN_MODULE_PACKAGES = {
        "io.github.fragudev.ailab.shared..",
        "io.github.fragudev.ailab.aiprovider..",
        "io.github.fragudev.ailab.conversation..",
        "io.github.fragudev.ailab.knowledge..",
        "io.github.fragudev.ailab.ingestion..",
        "io.github.fragudev.ailab.rag..",
        "io.github.fragudev.ailab.tools..",
        "io.github.fragudev.ailab.workflow..",
        "io.github.fragudev.ailab.mcp..",
        "io.github.fragudev.ailab.evaluation..",
        "io.github.fragudev.ailab.platform..",
    };

    private final JavaClasses classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(BASE_PACKAGE);

    @Test
    void noDomainModuleDependsOnApp() {
        ArchRule rule = noClasses()
                .that()
                .resideInAnyPackage(DOMAIN_MODULE_PACKAGES)
                .should()
                .dependOnClassesThat()
                .resideInAPackage(BASE_PACKAGE)
                .because("app is the composition root; no domain module may depend on it "
                        + "(docs/architecture.md #3, AGENTS.md Module boundaries)");

        rule.check(classes);
    }
}
