package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * One module schema is spelled in many places: the init scripts, the secret files, compose, the migration folder and
 * its callbacks, the test container and the restore drill. Phase 2 architecture review A5: nothing checked that they
 * agree, so a second module would have been left out of, for example, the drill's Flyway validate without a red test.
 */
class ModuleConsistencyTest {

    private static final Path ROOT = VersoPostgres.repoRoot();
    private static final Pattern MODULES = Pattern.compile("(?m)^MODULES=\\(([^)]*)\\)");

    /** ADR-0023: each module's migrations also obey the convention rules, with its own schema and application role. */
    @Test
    void everyModulesMigrations_whenScanned_followTheConventions() throws IOException {
        for (String module : modules("deploy/postgres/initdb/10-roles.sh")) {
            Path migrations = ROOT.resolve("services/" + module + "/" + module
                    + "-core/src/main/resources/db/migration/" + module);
            com.verso.document.migration.MigrationConventions rules =
                    new com.verso.document.migration.MigrationConventions(module, "svc_" + module);
            assertThat(rules.violations(migrations)).as(module).isEmpty();
            assertThat(rules.callbackViolations(migrations)).as(module + " callbacks").isEmpty();
        }
    }

    @Test
    void initScripts_whenDeclaringModules_agree() throws IOException {
        List<String> roles = modules("deploy/postgres/initdb/10-roles.sh");
        assertThat(modules("deploy/postgres/initdb/20-database.sh")).isEqualTo(roles);
        // The upgrade path for existing volumes and the restore drill's role checks (ADR-0023) know every module too.
        assertThat(modules("deploy/postgres/initdb/40-modules.sh")).isEqualTo(roles);
        assertThat(modules("deploy/backup/restore-check.sh")).isEqualTo(roles);
        assertThat(roles).as("ADR-0023, ADR-0026, ADR-0038: qa, audit and connector have their own schemas")
                .containsExactly("document", "qa", "audit", "connector");
    }

    @Test
    void everyModule_whenDeclared_hasItsSecretsMigrationsCallbacksAndDrill() throws IOException {
        String compose = read("compose.yaml");
        String devSecrets = read("scripts/dev-secrets.sh");
        List<String> modules = modules("deploy/postgres/initdb/10-roles.sh");
        assertThat(modules).isNotEmpty();
        for (String module : modules) {
            String upper = module.toUpperCase(Locale.ROOT);
            for (String secret : List.of("SECRET_DB_" + upper + "_PASSWORD", "SECRET_DB_" + upper + "_MIGRATE_PASSWORD")) {
                assertThat(devSecrets).as("dev-secrets.sh creates " + secret).contains(secret);
                assertThat(compose).as("compose.yaml declares " + secret).contains(secret + ":\n    file: ./secrets/" + secret);
                assertThat(VersoPostgres.secretNames()).as("test container gets " + secret).contains(secret);
            }
            Path migrations = ROOT.resolve("services/" + module + "/" + module
                    + "-core/src/main/resources/db/migration/" + module);
            assertThat(migrations).as("migration folder of " + module).isDirectory();
            for (String callback : List.of("afterMigrate.sql", "afterMigrateError.sql")) {
                assertThat(Files.readString(migrations.resolve(callback), StandardCharsets.UTF_8))
                        .as(module + " " + callback).contains("FROM svc_" + module + ";");
            }
            assertThat(compose).as("restore drill validates the migrations of " + module)
                    .contains("./services/" + module + "/" + module + "-core/src/main/resources/db/migration/" + module
                            + ":/flyway/migrations");
            // One validate step per module schema, as its own migration role (ADR-0009 open point, ADR-0023).
            assertThat(compose).as("restore drill validates " + module + " as its migration role")
                    .contains("-user=svc_" + module + "_migrate -schemas=" + module + " -defaultSchema=" + module + " ");
            String service = module.equals("document") ? "restore-flyway" : "restore-flyway-" + module;
            assertThat(read("scripts/restore-drill.sh")).as("restore-drill.sh runs " + service)
                    .containsPattern("run --rm --no-TTY " + service + "\\R");
        }
    }

    private static List<String> modules(String script) throws IOException {
        Matcher m = MODULES.matcher(read(script));
        assertThat(m.find()).as("MODULES=(...) in " + script).isTrue();
        return Arrays.stream(m.group(1).trim().split("\\s+")).filter(s -> !s.isBlank()).toList();
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
