package com.verso.qa.architecture;

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
 * The qa module's own rules (ADR-0007 #7; llm-rules 3.2): layering, only the document module's api, persistence only
 * in its own repositories (schema "qa", ADR-0023), and no tool calling (the model's output is never executed).
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
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
        classes().that().resideInAPackage(ROOT + ".service.impl..").and().areTopLevelClasses()
                .should().haveSimpleNameEndingWith("ServiceImpl").check(classes);
    }

    /** ADR-0003: qa reaches the document module through document-api only. */
    @Test
    void module_whenImported_usesOnlyTheDocumentApi() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..", "com.verso.document.api..", "com.verso.audit.api..")))
                .check(classes);
    }

    /**
     * ADR-0023: SQL lives in the repositories, on the module's own QaDatabase (schema "qa", role svc_qa); services
     * only open transactions through it. Nothing in qa may take the application DataSource or JdbcClient by type.
     */
    @Test
    void persistence_whenImported_staysInTheRepositories() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(classes);
        noClasses().that().resideInAnyPackage(ROOT + ".controller..", ROOT + ".worker..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..")
                .check(classes);
        // The application DataSource would connect as svc_document, which has no right on "qa" (and must get none).
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource")
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
