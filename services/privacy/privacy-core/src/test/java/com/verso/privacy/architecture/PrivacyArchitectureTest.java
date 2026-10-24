package com.verso.privacy.architecture;

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
import org.springframework.web.bind.annotation.RestController;

/**
 * The privacy module's own rules (ADR-0007 #7, ADR-0034): it owns no data, so it holds no SQL, no DataSource and no
 * transaction; it reaches the other modules through their apis only (AGENTS.md section 4).
 */
class PrivacyArchitectureTest {

    private static final String ROOT = "com.verso.privacy";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void layers_whenImported_dependOnlyDownwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Controller").definedBy(ROOT + ".controller..")
                .layer("Service").definedBy(ROOT + ".service..")
                .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller")
                .check(classes);
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
        classes().that().resideInAPackage(ROOT + ".service.impl..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("ServiceImpl").check(classes);
    }

    /** AGENTS.md section 4: other modules only through document-api, qa-api and audit-api. */
    @Test
    void module_whenImported_usesOnlyTheOtherModulesApis() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..", "com.verso.document.api..",
                                "com.verso.qa.api..", "com.verso.audit.api..")))
                .check(classes);
    }

    /** No schema of its own: every write is the owning module's, in the owning module's transaction. */
    @Test
    void module_whenImported_holdsNoPersistence() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..",
                        "org.springframework.transaction..", "javax.sql..")
                .check(classes);
    }
}
