package com.verso.work.architecture;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackages;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;
import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.web.bind.annotation.RestController;

/**
 * The work module's own rules (ADR-0007 #7, ADR-0057): layering, other modules only through their api, persistence
 * only in its repositories, the chat model only behind the SkillModel port (the gateway's integration point), the
 * domain rules free of Spring, and no SQL against another module's schema.
 */
class WorkArchitectureTest {

    private static final String ROOT = "com.verso.work";
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
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Worker", "Config")
                .whereLayer("Worker").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Config", "Worker")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".controller..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".repository..", ROOT + ".worker..",
                        ROOT + ".model..")
                .check(classes);
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", ROOT + ".repository..",
                        ROOT + ".service..", ROOT + ".config..", ROOT + ".model..", "org.springframework.ai..")
                .because("the definition, input, output and grounding rules stay pure functions")
                .check(classes);
    }

    /** Other application modules only through their api: document for retrieval, audit for events. */
    @Test
    void module_whenImported_usesOtherModulesOnlyThroughTheirApi() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..", "com.verso.document.api..",
                                "com.verso.audit.api..")))
                .check(classes);
    }

    /** The integration point of the model gateway: only the model package touches Spring AI. */
    @Test
    void chatModel_whenImported_isReachedOnlyThroughTheSkillModelPort() {
        noClasses().that().resideOutsideOfPackages(ROOT + ".model..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.ai..")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".config..")
                .should().dependOnClassesThat(resideInAPackage("org.springframework.ai..")
                        .and(not(com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleName("ChatModel"))))
                .check(classes);
    }

    @Test
    void persistence_whenImported_staysInTheRepositories() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(classes);
        noClasses().that().resideInAnyPackage(ROOT + ".controller..", ROOT + ".worker..", ROOT + ".service..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..")
                .check(classes);
    }

    /** No statement names another module's schema: documents are reached through document-api only. */
    @Test
    void sources_whenScanned_holdNoSqlOnAnotherSchema() throws IOException {
        Pattern sql = Pattern.compile("(?i)\\b(from|join|into|update)\\s+(document|qa|audit|connector)\\.");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            assertThat(sql.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }
}
