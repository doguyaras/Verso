package com.verso.document.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.RestController;

/**
 * The document module's own layering (ADR-0007 #7: every core module carries a copy of the reference's per-module
 * rules; phase 4 architecture review A1). The application-wide rules stay in verso-app's ArchitectureRulesTest.
 */
class DocumentArchitectureTest {

    private static final String ROOT = "com.verso.document";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    /** Controllers call services only; the worker owns its own transactions; repositories call nothing above them. */
    @Test
    void layers_whenImported_dependOnlyDownwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Config").definedBy(ROOT + ".config..")
                .layer("Controller").definedBy(ROOT + ".controller..")
                .layer("Service").definedBy(ROOT + ".service..")
                .layer("Worker").definedBy(ROOT + ".worker..")
                .layer("Repository").definedBy(ROOT + ".repository..")
                .whereLayer("Controller").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Config")
                .whereLayer("Worker").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Worker", "Config")
                .check(classes);
    }

    @Test
    void controllers_whenImported_neverTouchRepositoriesOrTheWorker() {
        noClasses().that().resideInAPackage(ROOT + ".controller..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".repository..", ROOT + ".worker..")
                .check(classes);
    }

    @Test
    void serviceImpl_whenImported_holdsOnlyServiceImplementations() {
        classes().that().resideInAPackage(ROOT + ".service.impl..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("ServiceImpl").check(classes);
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
        classes().that().areAnnotatedWith(Configuration.class).should().resideInAPackage(ROOT + ".config..")
                .check(classes);
    }

    /** No other application module and no platform internals: only the platform contracts and the own api. */
    @Test
    void module_whenImported_usesNoOtherApplicationModule() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..")))
                .check(classes);
    }
}
