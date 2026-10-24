package com.verso.document.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Rules for the migrations of the "document" schema (reference 10.1 "Zorlama — testle", 10.2), with the deliberate
 * violations that prove each rule. The rules themselves live in {@link MigrationConventions}, shared with every other
 * module schema (verso-app ModuleConsistencyTest, ADR-0023).
 */
class MigrationConventionsTest {

    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/document");
    static final Path BAD_FIXTURES = Path.of("src/test/resources/migration-fixtures/bad");
    static final MigrationConventions RULES = new MigrationConventions("document", "svc_document");

    @Test
    void migrations_whenScanned_followNamingSchemaAndPrivilegeRules() throws IOException {
        assertThat(MIGRATIONS).as("migration location").isDirectory();
        assertThat(RULES.violations(MIGRATIONS)).isEmpty();
    }

    /** afterMigrate and afterMigrateError (review D4): the history table never stays writable for the application. */
    @Test
    void callbacks_whenPresent_revokeTheHistoryTableFromTheApplicationRole() throws IOException {
        assertThat(RULES.callbackViolations(MIGRATIONS)).isEmpty();
    }

    /** The callback rule must see a callback that forgets the REVOKE (or names another module's role). */
    @Test
    void callbackRule_whenAnotherRoleIsNamed_reportsIt() throws IOException {
        assertThat(new MigrationConventions("document", "svc_other").callbackViolations(MIGRATIONS))
                .hasSize(2).allMatch(v -> v.contains("does not revoke"));
    }

    /** A green rule alone proves nothing: the same checks must reject every deliberate violation. */
    @Test
    void violations_whenFixturesBreakEachRule_areAllReported() throws IOException {
        List<String> found = RULES.violations(BAD_FIXTURES);
        assertThat(found).anyMatch(v -> v.contains("V7__Bad_Name.sql") && v.contains("file name"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'qa'"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'public'"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'quoted'"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'audit'"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("create\\s+schema"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("if\\s+not\\s+exists"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("\\bgrant\\b"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("REVOKE ALL ON SCHEMA"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("role\\b"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("owner\\s+to"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("set\\s+schema"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("set_config"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("execute"));
        assertThat(found).anyMatch(v -> v.contains("versions") && v.contains("expected 4"));
        // Every rule has its own fixture (phase 2 test review T9).
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("create\\s+extension"));
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("drop\\s+schema"));
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("search_path"));
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("default\\s+privileges"));
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("alter\\s+role"));
        assertThat(found).anyMatch(v -> v.contains("V6__more_infrastructure.sql") && v.contains("session\\s+authorization"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("version also used by"));
        assertThat(found).anyMatch(v -> v.contains("V8__evasions.sql") && v.contains("schema 'qa'"));
        assertThat(found).anyMatch(v -> v.contains("V8__evasions.sql") && v.contains("set\\s+schema"));
        assertThat(found).anyMatch(v -> v.contains("V5__no_comment.sql") && v.contains("comment"));
        // ...and no false alarm on the clean fixture: own schema, narrowing REVOKE, alias after ON, comment first.
        assertThat(found).noneMatch(v -> v.startsWith("V1__first.sql"));
    }

    /** ADR-0023: the same rules judge another module by its own schema; "document" is foreign there. */
    @Test
    void rules_whenAppliedToAnotherSchema_treatDocumentAsForeign() throws IOException {
        List<String> found = new MigrationConventions("qa", "svc_qa").violations(MIGRATIONS);
        assertThat(found).anyMatch(v -> v.contains("schema 'document' is not qa"));
        assertThat(found).anyMatch(v -> v.contains("only 'REVOKE <privileges> ON qa.<table> FROM svc_qa'"));
    }
}
