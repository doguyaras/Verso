package com.verso.audit.architecture;

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
 * The audit module's own rules (ADR-0007 #7, ADR-0026): layering, no dependency on another application module (the
 * document and qa modules publish to audit, so the reverse would be a cycle), persistence only in its repositories on
 * its own AuditDatabase, and an append-only code base: no statement that changes or removes an audit row.
 */
class AuditArchitectureTest {

    private static final String ROOT = "com.verso.audit";
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
        noClasses().that().resideInAPackage(ROOT + ".controller..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".repository..", ROOT + ".worker..")
                .check(classes);
        classes().that().areAnnotatedWith(RestController.class).should().resideInAPackage(ROOT + ".controller..")
                .check(classes);
    }

    /** audit depends on no other application module, only on the platform and its own api. */
    @Test
    void module_whenImported_dependsOnNoOtherApplicationModule() {
        noClasses().that().resideInAPackage(ROOT + "..")
                .should().dependOnClassesThat(resideInAPackage("com.verso..")
                        .and(resideOutsideOfPackages(ROOT + "..", "com.verso.platform..")))
                .check(classes);
    }

    @Test
    void persistence_whenImported_staysInTheRepositories() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.jdbc..", "java.sql..")
                .check(classes);
        noClasses().that().resideInAnyPackage(ROOT + ".controller..", ROOT + ".worker..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework.transaction..")
                .check(classes);
        noClasses().that().resideOutsideOfPackage(ROOT + ".repository..")
                .should().dependOnClassesThat().haveFullyQualifiedName("javax.sql.DataSource")
                .check(classes);
    }

    /** Append-only in code as well as in the grants: the only removal is the retention function. */
    @Test
    void sources_whenScanned_holdNoStatementThatChangesOrRemovesAnAuditRow() throws IOException {
        Pattern change = Pattern.compile("(?i)\\b(update\\s+audit\\.|delete\\s+from\\s+audit\\.|truncate\\s+(table\\s+)?audit\\.|"
                + "drop\\s+table|alter\\s+table|merge\\s+into|on\\s+conflict)");
        List<Path> sources;
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            sources = files.filter(p -> p.toString().endsWith(".java")).toList();
        }
        assertThat(sources).isNotEmpty();
        for (Path source : sources) {
            assertThat(change.matcher(Files.readString(source)).find()).as(source.toString()).isFalse();
        }
    }
}
