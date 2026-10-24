package com.verso.connector.architecture;

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
 * The connector module's own rules (ADR-0007 #7, ADR-0038): layering, other modules only through their api (document
 * for ingestion, audit for events), persistence only in its repositories on its own ConnectorDatabase, the SMB client
 * only in the smb package, and no SQL against another module's schema.
 */
class ConnectorArchitectureTest {

    private static final String ROOT = "com.verso.connector";
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
                .layer("Smb").definedBy(ROOT + ".smb..")
                .layer("Repository").definedBy(ROOT + ".repository..")
                .whereLayer("Controller").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Config")
                .whereLayer("Worker").mayOnlyBeAccessedByLayers("Config")
                .whereLayer("Smb").mayOnlyBeAccessedByLayers("Service", "Worker", "Config")
                .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Worker", "Config")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".controller..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".repository..", ROOT + ".worker..",
                        ROOT + ".smb..")
                .check(classes);
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
        noClasses().that().resideInAPackage(ROOT + ".domain..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..", ROOT + ".repository..",
                        ROOT + ".smb..", ROOT + ".config..")
                .because("the sync rules stay pure functions, tested without a file server or a database")
                .check(classes);
    }

    /** Other application modules only through their api: document for ingestion and deletion, audit for events. */
    @Test
    void module_whenImported_usesOtherModulesOnlyThroughTheirApi() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..", "com.verso.document.api..",
                                "com.verso.audit.api..")))
                .check(classes);
    }

    @Test
    void persistenceAndTheSmbClient_whenImported_stayInTheirPackages() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(classes);
        noClasses().that().resideInAnyPackage(ROOT + ".controller..", ROOT + ".worker..", ROOT + ".config..",
                        ROOT + ".smb..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..")
                .check(classes);
        noClasses().that().resideOutsideOfPackage(ROOT + ".smb..")
                .should().dependOnClassesThat().resideInAnyPackage("com.hierynomus..")
                .check(classes);
    }

    /** No statement names another module's schema: documents are reached through document-api only. */
    @Test
    void sources_whenScanned_holdNoSqlOnAnotherSchema() throws IOException {
        Pattern sql =Pattern.compile("(?i)\\b(from|join|into|update)\\s+(document|qa|audit)\\.");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            String text = Files.readString(source);
            assertThat(sql.matcher(text).find()).as(source.toString()).isFalse();
        }
    }
}
