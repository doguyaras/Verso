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

    @Test
    void initScripts_whenDeclaringModules_agree() throws IOException {
        assertThat(modules("deploy/postgres/initdb/20-database.sh")).isEqualTo(modules("deploy/postgres/initdb/10-roles.sh"));
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
        }
        // The drill validates one migration folder; a second module needs its own validate step (ADR-0009).
        assertThat(modules).as("modules covered by the restore drill's single Flyway validate").hasSize(1);
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
