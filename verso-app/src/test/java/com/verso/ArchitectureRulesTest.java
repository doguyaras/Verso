package com.verso;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.context.annotation.Configuration;

/**
 * Application-wide architecture rules (reference 4, 16, 19.5), run where every module is on the classpath.
 *
 * <p>The per-core rule set of the blueprint (layers, controller -> repository, service.impl, @Valid, core -> core)
 * is copied into each {@code *-core} module together with its first controller/service: ArchUnit fails on rules
 * that match no classes, and silencing that with allowEmptyShould would hide a "0 classes checked" false green
 * (ADR-0007 #7).
 *
 * <p>Plain JUnit {@code @Test} methods, not {@code @ArchTest}: ArchUnit's engine ran 0 tests under the Boot 4 BOM
 * in the reference skeleton (Ek B lesson 2).
 */
class ArchitectureRulesTest {

    static final String ROOT = "com.verso";
    static JavaClasses classes;

    /** Every package is its own slice, so a cycle between any two packages is caught (not only between modules). */
    /**
     * DO_NOT_INCLUDE_TESTS only knows test-classes folders; in a "verify" run a module's test-jar (platform-security's
     * test identity provider) is a jar and was imported as production code, while "test" saw the folder (phase 3
     * architecture review A2). Both runs now judge the same classes.
     */
    static final ImportOption NO_TEST_JARS = location -> !location.contains("-tests.jar");

    static final ArchRule NO_PACKAGE_CYCLES = slices().matching(ROOT + ".(**)").should().beFreeOfCycles();

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(NO_TEST_JARS)
                .importPackages(ROOT);
    }

    /**
     * Configuration classes live in a config package. Meta-annotations count, so @AutoConfiguration classes are
     * covered too (stricter than the blueprint, which only matched a direct @Configuration). The main class is the
     * one exception: it has to sit at the root package.
     */
    @Test
    void configurations_whenAnnotated_liveInConfigPackage() {
        classes().that().areMetaAnnotatedWith(Configuration.class)
                .and().areNotAnnotatedWith(SpringBootConfiguration.class)
                .and().areTopLevelClasses()
                .should().resideInAPackage("..config..")
                .check(classes);
    }

    @Test
    void packages_whenProductionCodeImported_haveNoCycles() {
        NO_PACKAGE_CYCLES.check(classes);
    }

    /** Negative counterpart: the cycle rule must see the deliberate cycle in test sources (a -> b -> a). */
    @Test
    void packageCycleRule_whenFixtureHasCycle_reportsIt() {
        JavaClasses fixture = new ClassFileImporter().importPackages(ROOT + ".archfixture.cycle");
        assertThatThrownBy(() -> NO_PACKAGE_CYCLES.check(fixture))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("archfixture.cycle.a")
                .hasMessageContaining("archfixture.cycle.b");
    }

    /** Platform starters are shared libraries: they may never see application or domain code. */
    @Test
    void platform_whenCompiled_doesNotDependOnApplicationModules() {
        noClasses().that().resideInAPackage(ROOT + ".platform..")
                .should().dependOnClassesThat(resideInAPackage(ROOT + "..")
                        .and(resideOutsideOfPackage(ROOT + ".platform..")))
                .because("platform-* starters must stay reusable and never depend on Verso's own modules")
                .check(classes);
    }
}
