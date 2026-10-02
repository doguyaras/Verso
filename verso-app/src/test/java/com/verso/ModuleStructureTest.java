package com.verso;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.importer.ImportOption;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Spring Modulith module boundaries (reference 1.1 shape A, 16) with the negative counterpart the reference demands:
 * a green verify() proves nothing unless it is shown to fail on a real violation.
 *
 * <p>The platform starters live under the root package but are shared libraries, not application modules: without
 * excluding them Modulith reports {@code platform} as a module and would flag every use of ServiceException as an
 * access to a non-exposed type (architecture review, 2026-10-02; observed with Modulith 2.1.1).
 */
class ModuleStructureTest {

    /** Application modules expected today; phase 5 adds "qa" (ADR-0001). */
    private static final Set<String> EXPECTED_MODULES = Set.of("document");

    static ApplicationModules productionModules() {
        return ApplicationModules.of(VersoApp.class, resideInAPackage("com.verso.platform.."));
    }

    @Test
    void productionCode_whenVerified_respectsModuleBoundaries() {
        ApplicationModules modules = productionModules();
        modules.verify();
        assertThat(modules.detectViolations().hasViolations()).isFalse();
    }

    @Test
    void productionModules_whenDetected_matchExpectedSetAndExcludePlatform() {
        Set<String> detected = productionModules().stream()
                .map(m -> m.getIdentifier().toString())
                .collect(Collectors.toSet());
        assertThat(detected).as("application modules").isEqualTo(EXPECTED_MODULES).doesNotContain("platform");
    }

    @Test
    void verify_whenModuleReachesIntoAnotherModulesInternals_reportsExactlyThatDependency() {
        // Production classes plus the two fixture modules from test sources (fixturealpha, fixturebeta). Modulith
        // 2.1.1 has no of(type, ignored, importOption), so the platform is left out through the import option here.
        ImportOption withFixtures = location -> !location.contains("/com/verso/platform/")
                && (!location.contains("/test-classes/")
                        || location.contains("/test-classes/com/verso/fixturealpha/")
                        || location.contains("/test-classes/com/verso/fixturebeta/"));
        ApplicationModules modules = ApplicationModules.of(VersoApp.class, withFixtures);
        assertThat(modules.stream().map(m -> m.getIdentifier().toString())).doesNotContain("platform");

        // The fixtures must really be attributed to modules, otherwise the test would pass vacuously.
        assertThat(modules.stream().map(ApplicationModule::getIdentifier).map(Object::toString))
                .contains("fixturealpha", "fixturebeta");

        assertThatThrownBy(modules::verify)
                .isInstanceOf(Violations.class)
                .hasMessageContaining("LeakyAlpha")
                .hasMessageContaining("com.verso.fixturebeta.internal.BetaInternal");

        // Only the leaky class is reported; the use of beta's public API is allowed.
        assertThat(modules.detectViolations().getMessages())
                .isNotEmpty()
                .allSatisfy(m -> assertThat(m).contains("LeakyAlpha"));
    }
}
