package com.verso.document.migration;

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

/**
 * The migration rules of one module schema (reference 10.1 "Zorlama — testle", 10.2). GRANT is the primary
 * enforcement of schema ownership; these rules catch the mistakes a migration can still make: touching another schema,
 * creating schemas or extensions (infrastructure work, deploy/postgres/initdb), widening privileges, switching roles or
 * owners, hiding drift with IF NOT EXISTS, hiding names in dynamic SQL, and versions that skip or repeat.
 *
 * <p>Allowed on purpose: narrowing the application role's rights on an own table, e.g. an append-only audit table
 * (reference 10.1: "Append-only/audit tablolarında uygulama rolünden UPDATE/DELETE ayrıca REVOKE edilir").
 *
 * <p>Parameterised by schema and application role (ADR-0023): the document module checks its own folder here
 * (MigrationConventionsTest, with the bad fixtures), verso-app's ModuleConsistencyTest checks every module's folder
 * with the same rules. Shipped in document-core's test-jar.
 */
public final class MigrationConventions {

    private static final Pattern VERSIONED = Pattern.compile("V([1-9][0-9]*)__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    private static final Pattern REPEATABLE = Pattern.compile("R__[a-z0-9]+(_[a-z0-9]+)*\\.sql");
    /** Flyway SQL callbacks of a module; both take the history table back from the application role. */
    public static final List<String> CALLBACKS = List.of("afterMigrate.sql", "afterMigrateError.sql");
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
    /** Keywords after which a qualified name is a schema-qualified object, not table_alias.column. */
    private static final Pattern OBJECT_CONTEXT = Pattern.compile("(?s).*\\b(table|from|join|into|update|references|"
            + "index|view|sequence|function|procedure|type|trigger|exists|extension|to|schema|truncate|using|"
            + "lock|copy|analyze|vacuum|comment\\s+on\\s+\\w+)\\s+(only\\s+)?$");
    /** Words that end a FROM list: after them, ", x.y" is not another table of a comma join. */
    private static final Pattern FROM_LIST_END = Pattern.compile(
            "(?i)\\b(where|on|group|order|having|limit|returning|set|values|union|join|using)\\b");

    private final String ownSchema;
    private final String applicationRole;
    /** Schemas a migration may name: its own and the shared extension schema (vector type and operators). */
    private final List<String> allowedSchemas;
    private final Pattern narrowingRevoke;

    public MigrationConventions(String ownSchema, String applicationRole) {
        this.ownSchema = ownSchema;
        this.applicationRole = applicationRole;
        this.allowedSchemas = List.of(ownSchema, "extensions", "pg_catalog");
        this.narrowingRevoke = Pattern.compile("(?is)revoke\\s+(select|insert|update|delete|"
                + "truncate|references|trigger)(\\s*,\\s*(select|insert|update|delete|truncate|references|trigger))*\\s+on\\s+"
                + "(table\\s+)?" + Pattern.quote(ownSchema) + "\\.[a-z_][a-z0-9_]*\\s+from\\s+"
                + Pattern.quote(applicationRole) + "\\s*;?");
    }

    /** afterMigrate and afterMigrateError (review D4): the history table never stays writable for the application. */
    public List<String> callbackViolations(Path dir) throws IOException {
        List<String> found = new ArrayList<>();
        for (String callback : CALLBACKS) {
            Path file = dir.resolve(callback);
            if (!Files.isRegularFile(file)) {
                found.add(callback + ": missing");
                continue;
            }
            String sql = stripComments(Files.readString(file, StandardCharsets.UTF_8));
            if (!sql.contains("REVOKE ALL ON \"${flyway:defaultSchema}\".\"${flyway:table}\" FROM " + applicationRole)) {
                found.add(callback + ": does not revoke the history table from " + applicationRole);
            }
        }
        return found;
    }

    public List<String> violations(Path dir) throws IOException {
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
                        if (!narrowingRevoke.matcher(revoke.group().trim()).matches()) {
                            found.add(name + ": only 'REVOKE <privileges> ON " + ownSchema + ".<table> FROM "
                                    + applicationRole + "' is allowed, found: "
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
                    if (!allowedSchemas.contains(schema) && isSchemaQualified(sql, qualified.start(), qualified.end())) {
                        found.add(name + ": schema '" + schema + "' is not " + ownSchema);
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

    public static String stripComments(String sql) {
        return sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("--[^\\n]*", " ");
    }
}
