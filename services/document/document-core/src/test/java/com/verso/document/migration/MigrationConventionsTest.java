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
 * schema, creating schemas or extensions (infrastructure work, deploy/postgres/initdb), widening privileges, switching
 * roles or owners, hiding drift with IF NOT EXISTS, hiding names in dynamic SQL, and versions that skip or repeat.
 *
 * <p>Allowed on purpose: narrowing the application role's rights on an own table, e.g. an append-only audit table
 * (reference 10.1: "Append-only/audit tablolarında uygulama rolünden UPDATE/DELETE ayrıca REVOKE edilir").
 */
class MigrationConventionsTest {

    static final String OWN_SCHEMA = "document";
    static final String APPLICATION_ROLE = "svc_document";
    static final Path MIGRATIONS = Path.of("src/main/resources/db/migration/document");
    static final Path BAD_FIXTURES = Path.of("src/test/resources/migration-fixtures/bad");

    private static final Pattern VERSIONED = Pattern.compile("V([1-9][0-9]*)__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    private static final Pattern REPEATABLE = Pattern.compile("R__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    /** Flyway SQL callbacks of this module; both take the history table back from the application role. */
    static final List<String> CALLBACKS = List.of("afterMigrate.sql", "afterMigrateError.sql");
    /** Schemas a migration may name: its own and the shared extension schema (vector type and operators). */
    private static final List<String> ALLOWED_SCHEMAS = List.of(OWN_SCHEMA, "extensions", "pg_catalog");
    /** schema.object, either part optionally double-quoted ("qa".answer, qa."answer"). */
    private static final Pattern QUALIFIED =
            Pattern.compile("(?i)(?<![a-z0-9_\"$])\"?([a-z_][a-z0-9_]*)\"?\\s*\\.\\s*\"?([a-z_][a-z0-9_]*)\"?");
    private static final List<Pattern> FORBIDDEN = List.of(
            Pattern.compile("(?i)\\bcreate\\s+schema\\b"),
            Pattern.compile("(?i)\\bdrop\\s+schema\\b"),
            Pattern.compile("(?i)\\bcreate\\s+extension\\b"),
            Pattern.compile("(?i)\\bif\\s+not\\s+exists\\b"),
            Pattern.compile("(?i)\\bset\\s+(local\\s+|session\\s+)?search_path\\b"),
            Pattern.compile("(?i)set_config\\s*\\(\\s*'search_path'"),
            Pattern.compile("(?i)\\bset\\s+(local\\s+|session\\s+)?role\\b"),
            Pattern.compile("(?i)\\bset\\s+session\\s+authorization\\b"),
            Pattern.compile("(?i)\\bowner\\s+to\\b"),
            Pattern.compile("(?i)\\bset\\s+schema\\b"),
            Pattern.compile("(?i)\\balter\\s+default\\s+privileges\\b"),
            Pattern.compile("(?i)\\bgrant\\b"),
            Pattern.compile("(?i)\\balter\\s+role\\b"),
            // Dynamic SQL hides schema and object names from every check above.
            Pattern.compile("(?i)\\bexecute\\b"));
    /** The only REVOKE a migration may contain: narrowing the application role on an own table. */
    private static final Pattern REVOKE_STATEMENT = Pattern.compile("(?is)\\brevoke\\b[^;]*;?");
    private static final Pattern NARROWING_REVOKE = Pattern.compile("(?is)revoke\\s+(select|insert|update|delete|"
            + "truncate|references|trigger)(\\s*,\\s*(select|insert|update|delete|truncate|references|trigger))*\\s+on\\s+"
            + "(table\\s+)?" + OWN_SCHEMA + "\\.[a-z_][a-z0-9_]*\\s+from\\s+" + APPLICATION_ROLE + "\\s*;?");
    /** Keywords after which a qualified name is a schema-qualified object, not table_alias.column. */
    private static final Pattern OBJECT_CONTEXT = Pattern.compile("(?s).*\\b(table|from|join|into|update|references|"
            + "index|view|sequence|function|procedure|type|trigger|exists|extension|to|schema|truncate|using|"
            + "lock|copy|analyze|vacuum|comment\\s+on\\s+\\w+)\\s+(only\\s+)?$");
    /** Words that end a FROM list: after them, ", x.y" is not another table of a comma join. */
    private static final Pattern FROM_LIST_END = Pattern.compile(
            "(?i)\\b(where|on|group|order|having|limit|returning|set|values|union|join|using)\\b");

    @Test
    void migrations_whenScanned_followNamingSchemaAndPrivilegeRules() throws IOException {
        assertThat(MIGRATIONS).as("migration location").isDirectory();
        assertThat(violations(MIGRATIONS)).isEmpty();
    }

    /** afterMigrate and afterMigrateError (review D4): the history table never stays writable for the application. */
    @Test
    void callbacks_whenPresent_revokeTheHistoryTableFromTheApplicationRole() throws IOException {
        for (String callback : CALLBACKS) {
            String sql = Files.readString(MIGRATIONS.resolve(callback), StandardCharsets.UTF_8);
            assertThat(stripComments(sql)).as(callback)
                    .contains("REVOKE ALL ON \"${flyway:defaultSchema}\".\"${flyway:table}\" FROM " + APPLICATION_ROLE);
        }
    }

    /** A green rule alone proves nothing: the same checks must reject every deliberate violation. */
    @Test
    void violations_whenFixturesBreakEachRule_areAllReported() throws IOException {
        List<String> found = violations(BAD_FIXTURES);
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
                String raw = Files.readString(file, StandardCharsets.UTF_8);
                String sql = stripComments(raw);
                if (!callback) {
                    for (Pattern rule : FORBIDDEN) {
                        if (rule.matcher(sql).find()) found.add(name + ": forbidden " + rule.pattern());
                    }
                    Matcher revoke = REVOKE_STATEMENT.matcher(sql);
                    while (revoke.find()) {
                        if (!NARROWING_REVOKE.matcher(revoke.group().trim()).matches()) {
                            found.add(name + ": only 'REVOKE <privileges> ON " + OWN_SCHEMA + ".<table> FROM "
                                    + APPLICATION_ROLE + "' is allowed, found: "
                                    + revoke.group().trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT));
                        }
                    }
                    if (!raw.stripLeading().startsWith("--")) {
                        found.add(name + ": must start with a comment (why the change, reference 10.2)");
                    }
                }
                Matcher qualified = QUALIFIED.matcher(sql);
                while (qualified.find()) {
                    String schema = qualified.group(1).toLowerCase(Locale.ROOT);
                    if (!ALLOWED_SCHEMAS.contains(schema) && isSchemaQualified(sql, qualified.start(), qualified.end())) {
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

    /**
     * Tells schema.object from alias.column by context: an object keyword before it (FROM, JOIN, TABLE, ...), "ON"
     * only when an index or trigger target follows ("ON qa.t (" or "ON qa.t USING"), or a comma inside a FROM list
     * (comma join). "JOIN qa.x a ON a.id = ..." therefore reports qa and not the alias a (review A4).
     */
    private static boolean isSchemaQualified(String sql, int start, int end) {
        int statementStart = sql.lastIndexOf(';', start) + 1;
        String before = sql.substring(statementStart, start).toLowerCase(Locale.ROOT);
        String after = sql.substring(end, Math.min(sql.length(), end + 20)).toLowerCase(Locale.ROOT);
        if (OBJECT_CONTEXT.matcher(before).matches()) return true;
        if (before.matches("(?s).*\\bon\\s+$")) return after.matches("(?s)\\s*(\\(|using\\b).*");
        if (before.matches("(?s).*,\\s*$")) {
            int from = before.lastIndexOf("from");
            return from >= 0 && !FROM_LIST_END.matcher(before.substring(from + 4)).find();
        }
        return false;
    }

    static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }
}
