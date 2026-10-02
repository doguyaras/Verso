package com.verso.document.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Rules for the migrations of the "document" schema (reference 10.1 "Zorlama — testle", 10.2). GRANT is the primary
 * enforcement of schema ownership; this test catches the mistakes a migration can still make: touching another
 * schema, creating schemas or extensions (infrastructure work, deploy/postgres/initdb), changing privileges outside
 * the afterMigrate callback, hiding drift with IF NOT EXISTS, and versions that skip or repeat.
 */
class MigrationConventionsTest {

    static final String OWN_SCHEMA = "document";
    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/document");
    static final Path BAD_FIXTURES = Path.of("src/test/resources/migration-fixtures/bad");

    private static final Pattern VERSIONED = Pattern.compile("V([1-9][0-9]*)__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    private static final Pattern REPEATABLE = Pattern.compile("R__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    /** Flyway SQL callbacks used by this module. */
    private static final List<String> CALLBACKS = List.of("afterMigrate.sql");
    /** Schemas a migration may name: its own and the shared extension schema (vector type and operators). */
    private static final List<String> ALLOWED_SCHEMAS = List.of(OWN_SCHEMA, "extensions", "pg_catalog");
    private static final Pattern QUALIFIED = Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\s*\\.\\s*[a-z_\"]");
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)\\bcreate\\s+schema\\b"),
            Pattern.compile("(?i)\\bdrop\\s+schema\\b"),
            Pattern.compile("(?i)\\bcreate\\s+extension\\b"),
            Pattern.compile("(?i)\\bif\\s+not\\s+exists\\b"),
            Pattern.compile("(?i)\\bset\\s+(local\\s+)?search_path\\b"),
            Pattern.compile("(?i)\\balter\\s+default\\s+privileges\\b"),
            Pattern.compile("(?i)\\b(grant|revoke)\\b"),
            Pattern.compile("(?i)\\balter\\s+role\\b"));

    @Test
    void migrations_whenScanned_followNamingSchemaAndPrivilegeRules() throws IOException {
        assertThat(MIGRATIONS).as("migration location").isDirectory();
        assertThat(violations(MIGRATIONS)).isEmpty();
    }

    @Test
    void afterMigrateCallback_whenPresent_revokesTheHistoryTableFromTheApplicationRole() throws IOException {
        String callback = Files.readString(MIGRATIONS.resolve("afterMigrate.sql"), StandardCharsets.UTF_8);
        assertThat(stripComments(callback))
                .contains("REVOKE ALL ON \"${flyway:defaultSchema}\".\"${flyway:table}\" FROM svc_document");
    }

    /** A green rule alone proves nothing: the same checks must reject every deliberate violation. */
    @Test
    void violations_whenFixturesBreakEachRule_areAllReported() throws IOException {
        List<String> found = violations(BAD_FIXTURES);
        assertThat(found).anyMatch(v -> v.contains("V7__Bad_Name.sql") && v.contains("file name"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'qa'"));
        assertThat(found).anyMatch(v -> v.contains("V2__other_schema.sql") && v.contains("schema 'public'"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("create\\s+schema"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("if\\s+not\\s+exists"));
        assertThat(found).anyMatch(v -> v.contains("V3__infrastructure.sql") && v.contains("(grant|revoke)"));
        assertThat(found).anyMatch(v -> v.contains("versions") && v.contains("expected 4"));
        assertThat(found).anyMatch(v -> v.contains("V5__no_comment.sql") && v.contains("comment"));
        // ...and no false alarm on the clean fixture (own schema, comment first, valid name).
        assertThat(found).noneMatch(v -> v.startsWith("V1__first.sql"));
    }

    static List<String> violations(Path dir) throws IOException {
        List<String> found = new ArrayList<>();
        TreeMap<Integer, String> versions = new TreeMap<>();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.sorted().toList()) {
                String name = file.getFileName().toString();
                Matcher versioned = VERSIONED.matcher(name);
                boolean callback = CALLBACKS.contains(name);
                if (versioned.matches()) {
                    String previous = versions.put(Integer.parseInt(versioned.group(1)), name);
                    if (previous != null) found.add(name + ": version also used by " + previous);
                } else if (!callback && !REPEATABLE.matcher(name).matches()) {
                    found.add(name + ": file name is not V<n>__<snake>.sql, R__<snake>.sql or a known callback");
                    continue;
                }
                String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
                if (!callback) {
                    for (Pattern rule : FORBIDDEN) {
                        if (rule.matcher(sql).find()) found.add(name + ": forbidden " + rule.pattern());
                    }
                    if (!Files.readString(file, StandardCharsets.UTF_8).stripLeading().startsWith("--")) {
                        found.add(name + ": must start with a comment (why the change, reference 10.2)");
                    }
                }
                Matcher qualified = QUALIFIED.matcher(sql);
                while (qualified.find()) {
                    String schema = qualified.group(1).toLowerCase(Locale.ROOT);
                    if (!ALLOWED_SCHEMAS.contains(schema) && looksLikeSchema(sql, qualified.start(1))) {
                        found.add(name + ": schema '" + schema + "' is not " + OWN_SCHEMA);
                    }
                }
            }
        }
        int expected = 1;
        for (int version : versions.keySet()) {
            if (version != expected) {
                found.add("versions: " + versions.keySet() + ", expected " + expected + " next (no gaps, no jumps)");
                break;
            }
            expected++;
        }
        return found;
    }

    /** Excludes table aliases used as qualifiers (t.id) by requiring a FROM/JOIN/ON/TABLE-like keyword context. */
    private static boolean looksLikeSchema(String sql, int at) {
        String before = sql.substring(Math.max(0, at - 40), at).toLowerCase(Locale.ROOT);
        return before.matches("(?s).*\\b(table|from|join|into|update|on|references|index|view|sequence|function|"
                + "type|trigger|exists|extension|to|schema|truncate)\\s+(only\\s+)?$");
    }

    private static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }
}
