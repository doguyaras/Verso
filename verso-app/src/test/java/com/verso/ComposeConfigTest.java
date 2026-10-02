package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * compose.yaml is configuration too (reference 15.1, 18.2; ADR-0009). Phase 2 reviews: a placeholder added to
 * config/verso.yml and the env template but not to compose passed every test and broke "docker compose up" (E4);
 * nothing kept secrets out of compose environments and .env.example (E11); the migration password must stay out of
 * the long-running application (S4).
 */
class ComposeConfigTest {

    private static final Path ROOT = VersoPostgres.repoRoot();
    /** ${VAR}, ${VAR:-default}, ${VAR-default}; "$${VAR}" is escaped for the container shell and not compose's. */
    private static final Pattern COMPOSE_VAR = Pattern.compile("(?<!\\$)\\$\\{([A-Z_][A-Z0-9_]*)(?::?-[^}]*)?}");
    private static final Pattern SPRING_PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(:[^}]*)?}");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(password|passwd|secret|token|api[_-]?key|credential)");
    /** Run-time arguments of scripts/restore-drill*.sh, not settings. */
    private static final Set<String> RUNTIME_ARGUMENTS = Set.of("BACKUP_FILE", "RESTORE_OPTIONS");

    @Test
    void javaServices_whenStarted_getEveryNonSecretPlaceholderOfTheServiceConfig() throws IOException {
        Set<String> placeholders = new TreeSet<>();
        Matcher m = SPRING_PLACEHOLDER.matcher(read("verso-app/src/main/resources/config/verso.yml"));
        while (m.find()) if (!m.group(1).startsWith("SECRET_")) placeholders.add(m.group(1));
        assertThat(placeholders).as("placeholders in config/verso.yml").contains("DB_HOST", "DB_PORT", "DB_NAME");

        for (String service : List.of("verso-app", "migrate")) {
            assertThat(environment(service).keySet()).as(service + " environment").containsAll(placeholders);
        }
    }

    @Test
    void secrets_whenComposed_neverTravelAsEnvironmentOrInEnvExample() throws IOException {
        for (Map.Entry<String, Object> service : services().entrySet()) {
            for (Map.Entry<String, Object> env : environment(service.getKey()).entrySet()) {
                if (env.getKey().endsWith("_FILE")) continue; // a path to a /run/secrets file, not a value
                assertThat(SECRET_NAME.matcher(env.getKey()).find())
                        .as("%s: environment %s looks like a secret", service.getKey(), env.getKey()).isFalse();
                assertThat(String.valueOf(env.getValue())).as("%s: %s", service.getKey(), env.getKey())
                        .doesNotContain("SECRET_");
            }
        }
        for (String line : read(".env.example").split("\n")) {
            if (line.isBlank() || line.startsWith("#")) continue;
            String key = line.substring(0, line.indexOf('='));
            assertThat(SECRET_NAME.matcher(key).find()).as(".env.example key " + key).isFalse();
        }
    }

    @Test
    void composeSettings_whenReferenced_areDocumentedInEnvExample() throws IOException {
        Set<String> used = new TreeSet<>();
        for (String file : List.of("compose.yaml", "deploy/compose.local.yaml")) {
            Matcher m = COMPOSE_VAR.matcher(read(file));
            while (m.find()) used.add(m.group(1));
        }
        used.removeAll(RUNTIME_ARGUMENTS);
        Set<String> documented = new TreeSet<>();
        for (String line : read(".env.example").split("\n")) {
            if (!line.isBlank() && !line.startsWith("#")) documented.add(line.substring(0, line.indexOf('=')));
        }
        assertThat(documented).as(".env.example").containsAll(used);
    }

    /** Review S4: a compromised application must not hold the schema owner's password. */
    @Test
    void migrationPassword_whenComposed_reachesOnlyTheOneShotMigrateService() {
        assertThat(secrets("verso-app")).containsExactly("SECRET_DB_DOCUMENT_PASSWORD");
        assertThat(environment("verso-app")).containsEntry("SPRING_FLYWAY_ENABLED", "false");
        assertThat(secrets("migrate")).containsExactly("SECRET_DB_DOCUMENT_MIGRATE_PASSWORD");
        assertThat(service("migrate")).containsEntry("restart", "no");
        @SuppressWarnings("unchecked")
        Map<String, Object> dependsOn = (Map<String, Object>) service("verso-app").get("depends_on");
        assertThat(dependsOn).containsKey("migrate");
        assertThat(String.valueOf(dependsOn.get("migrate"))).contains("service_completed_successfully");
    }

    /** Only the API is published, and only on the loopback interface (reference 18.2). */
    @Test
    void ports_whenComposed_publishOnlyTheApiOnLoopback() {
        List<String> published = new ArrayList<>();
        services().forEach((name, definition) -> {
            Object ports = ((Map<?, ?>) definition).get("ports");
            if (ports instanceof List<?> list) list.forEach(p -> published.add(name + " " + p));
        });
        assertThat(published).hasSize(1);
        assertThat(published.getFirst()).startsWith("verso-app 127.0.0.1:").endsWith(":8080");
    }

    /** Reference 18.2 hardening of every container that does not need root to start (phase 2 test review T8). */
    @Test
    void hardening_whenServicesStart_isReadOnlyWithoutCapabilities() {
        for (String name : List.of("verso-app", "migrate", "backup", "restore-runner", "restore-flyway")) {
            Map<String, Object> s = service(name);
            assertThat(s.get("read_only")).as(name).isEqualTo(Boolean.TRUE);
            assertThat(s.get("cap_drop")).as(name).isEqualTo(List.of("ALL"));
            assertThat(s.get("security_opt")).as(name).isEqualTo(List.of("no-new-privileges:true"));
        }
        for (String name : List.of("postgres", "restore-db")) {
            assertThat(service(name).get("security_opt")).as(name).isEqualTo(List.of("no-new-privileges:true"));
        }
    }

    /** Resolved per service (YAML aliases included), so an unpinned image behind another anchor is caught too. */
    @Test
    void images_whenResolvedPerService_arePinnedByDigest() {
        services().forEach((name, definition) -> {
            Map<?, ?> service = (Map<?, ?>) definition;
            String image = String.valueOf(service.get("image"));
            if (service.containsKey("build") || image.startsWith("verso:")) return; // our own image, tagged by CI
            assertThat(image).as(name).containsPattern("@sha256:[0-9a-f]{64}$");
        });
    }

    /** The final image stage runs as a non-root numeric user, set before the entrypoint (reference 18.1). */
    @Test
    void dockerfile_whenFinalStageRuns_usesANonRootUser() throws IOException {
        String dockerfile = read("Dockerfile");
        String finalStage = dockerfile.substring(dockerfile.lastIndexOf("\nFROM "));
        Matcher user = Pattern.compile("(?m)^USER\\s+(\\S+)\\s*$").matcher(finalStage);
        assertThat(user.find()).as("USER in the final stage").isTrue();
        assertThat(user.group(1)).matches("[1-9][0-9]*");
        assertThat(finalStage.indexOf("\nUSER ")).isLessThan(finalStage.indexOf("\nENTRYPOINT "));
    }

    /** Review S5: root-only patterns let verso-app/.env or src/main/resources/.env.prod into the jar and the image. */
    @Test
    void dockerignore_whenBuilding_keepsSecretsAndEnvFilesOutAtEveryDepth() throws IOException {
        List<String> patterns = read(".dockerignore").lines().map(String::trim).toList();
        assertThat(patterns).contains("**/.env", "**/.env.*", "**/secrets/", "**/target", ".git");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> services() {
        try {
            Map<String, Object> compose = new Yaml().load(read("compose.yaml"));
            return (Map<String, Object>) compose.get("services");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> service(String name) {
        Map<String, Object> service = (Map<String, Object>) services().get(name);
        assertThat(service).as("service " + name).isNotNull();
        return service;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> environment(String name) {
        Object env = service(name).get("environment");
        return env == null ? Map.of() : (Map<String, Object>) env;
    }

    private static Set<String> secrets(String name) {
        Set<String> result = new LinkedHashSet<>();
        Object list = service(name).get("secrets");
        if (list instanceof List<?> l) l.forEach(s -> result.add(String.valueOf(s)));
        return result;
    }

    private static String read(String file) throws IOException {
        return Files.readString(ROOT.resolve(file), StandardCharsets.UTF_8);
    }
}
