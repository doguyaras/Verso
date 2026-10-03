package com.verso.qa.architecture;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The qa module's own rules (ADR-0007 #7; llm-rules 3.2): layering, only the document module's api, no persistence,
 * and no tool calling (the model's output is never executed).
 */
class QaArchitectureTest {

    private static final String ROOT = "com.verso.qa";
    private static JavaClasses classes;

    @BeforeAll
    static void importClasses() {
        classes = new ClassFileImporter().withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(ROOT);
    }

    @Test
    void layers_whenImported_dependOnlyDownwards() {
        layeredArchitecture().consideringOnlyDependenciesInLayers()
                .layer("Config").definedBy(ROOT + ".config..")
                .layer("Controller").definedBy(ROOT + ".controller..")
                .layer("Service").definedBy(ROOT + ".service..")
                .whereLayer("Controller").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Config")
                .check(classes);
    }

    /** ADR-0003: qa reaches the document module through document-api only and touches no table. */
    @Test
    void module_whenImported_usesOnlyTheDocumentApiAndNoPersistence() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..", "com.verso.document.api..")))
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..",
                        "org.springframework.transaction..")
                .check(classes);
    }

    /** llm-rules 3.2: no tools, no function callbacks, no content-logging advisor (2.2). */
    @Test
    void module_whenImported_givesTheModelNoToolsAndLogsNoContent() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai.tool..",
                        "org.springframework.ai.model.tool..")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat().haveSimpleName("SimpleLoggerAdvisor")
                .check(classes);
    }
}
