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
        for (String file : List.of("compose.yaml", "deploy/compose.local.yaml", "deploy/compose.cloud.yaml")) {
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
        assertThat(secrets("keycloak")).as("the IdP gets its own secrets only").containsExactlyInAnyOrder(
                "SECRET_DB_KEYCLOAK_PASSWORD", "SECRET_KEYCLOAK_ADMIN_PASSWORD", "SECRET_KEYCLOAK_CI_CLIENT_SECRET",
                "SECRET_KEYCLOAK_DEMO_USER_PASSWORD", "SECRET_KEYCLOAK_PANEL_ADMIN_PASSWORD");
        @SuppressWarnings("unchecked")
        Map<String, Object> dependsOn = (Map<String, Object>) service("verso-app").get("depends_on");
        assertThat(dependsOn).containsKey("migrate");
        assertThat(String.valueOf(dependsOn.get("migrate"))).contains("service_completed_successfully");
    }

    /** Only the API (through the edge proxy) and the demo IdP are published, only on the loopback interface (18.2). */
    @Test
    void ports_whenComposed_publishOnlyTheApiAndTheIdpOnLoopback() {
        List<String> published = new ArrayList<>();
        services().forEach((name, definition) -> {
            Object ports = ((Map<?, ?>) definition).get("ports");
            if (ports instanceof List<?> list) list.forEach(p -> published.add(name + " " + p));
        });
        // The API, the demo IdP (device-flow login, CI token) and Grafana (profile "obs"); only on the loopback interface.
        assertThat(published).hasSize(3);
        assertThat(published).anyMatch(p -> p.startsWith("obs-edge 127.0.0.1:") && p.endsWith(":3000"));
        assertThat(published).anyMatch(p -> p.startsWith("edge 127.0.0.1:") && p.endsWith(":8080"));
        assertThat(published).anyMatch(p -> p.startsWith("keycloak 127.0.0.1:") && p.endsWith(":8080"));
    }

    /** Reference 18.2 hardening of every container that does not need root to start (phase 2 test review T8). */
    @Test
    void hardening_whenServicesStart_isReadOnlyWithoutCapabilities() {
        for (String name : List.of("verso-app", "migrate", "backup", "restore-runner", "restore-flyway", "ollama",
                "ollama-pull", "edge", "prometheus", "alertmanager", "loki", "alloy", "grafana", "obs-edge")) {
            Map<String, Object> s = service(name);
            assertThat(s.get("read_only")).as(name).isEqualTo(Boolean.TRUE);
            assertThat(s.get("cap_drop")).as(name).isEqualTo(List.of("ALL"));
            assertThat(s.get("security_opt")).as(name).isEqualTo(List.of("no-new-privileges:true"));
        }
        // Keycloak writes its optimised build under /opt/keycloak at startup: not read_only, but without capabilities.
        assertThat(service("keycloak").get("cap_drop")).isEqualTo(List.of("ALL"));
        for (String name : List.of("postgres", "restore-db", "keycloak")) {
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

    /**
     * llm-rules 1.1/8.2, ADR-0011: the running model server has no route out (only the internal "models" network), no
     * cloud models, reads the models read-only and serves the model the pull container verified; the application does
     * not wait for it (review E1) and the one-shot migrate run builds no model client.
     */
    @Test
    @SuppressWarnings("unchecked")
    void ollama_whenComposed_isIsolatedReadOnlyAndServesThePulledModel() throws IOException {
        Map<String, Object> compose = new Yaml().load(read("compose.yaml"));
        Map<String, Object> networks = (Map<String, Object>) compose.get("networks");
        assertThat((Map<String, Object>) networks.get("models")).containsEntry("internal", true);

        Map<String, Object> ollama = service("ollama");
        assertThat(ollama.get("networks")).isEqualTo(List.of("models"));
        assertThat(ollama).doesNotContainKey("ports");
        assertThat((List<String>) ollama.get("volumes")).singleElement().asString().endsWith(":ro");
        assertThat(environment("ollama")).containsEntry("OLLAMA_NO_CLOUD", "true");
        // Every pulled model is pinned by digest; the healthcheck asks for each; the application uses exactly them.
        List<String> pulled = List.of(String.valueOf(environment("ollama-pull").get("OLLAMA_PULL")).trim().split("\\s+"));
        assertThat(pulled).hasSize(2).allSatisfy(entry -> assertThat(entry)
                .matches(".+@(sha256:[0-9a-f]{64}|\\$\\{[A-Z_]+:-sha256:[0-9a-f]{64}})"));
        String embedding = pulled.get(0).substring(0, pulled.get(0).indexOf('@'));
        String chat = pulled.get(1).substring(0, pulled.get(1).lastIndexOf("}@") + 1);
        String healthcheck = String.valueOf(((Map<String, Object>) ollama.get("healthcheck")).get("test"));
        assertThat(healthcheck).contains(embedding).contains(chat);
        assertThat(environment("verso-app")).containsEntry("OLLAMA_EMBEDDING_MODEL", embedding)
                .containsEntry("OLLAMA_CHAT_MODEL", chat);

        assertThat((List<String>) service("verso-app").get("networks")).contains("models");
        assertThat((Map<String, Object>) service("verso-app").get("depends_on")).doesNotContainKey("ollama");
        assertThat((List<String>) service("migrate").get("command"))
                .contains("--spring.ai.model.embedding=none", "--spring.ai.model.chat=none");
    }

    /**
     * ADR-0006 decision 1.1, ADR-0013: in local mode nothing of Verso has a route out. The application, its database
     * and the model server are on internal networks only; the edge proxy and the IdP join "default" for their published
     * ports, the one-shot model download for the internet. Only the cloud overlay gives the application a gateway,
     * together with cloud mode and the key file, which reaches no other service.
     */
    @Test
    @SuppressWarnings("unchecked")
    void networks_whenComposed_keepTheApplicationWithoutARouteOutUnlessCloud() throws IOException {
        Map<String, Object> networks = (Map<String, Object>) new Yaml().<Map<String, Object>>load(read("compose.yaml"))
                .get("networks");
        assertThat((Map<String, Object>) networks.get("backend")).containsEntry("internal", true);
        assertThat((Map<String, Object>) networks.get("models")).containsEntry("internal", true);
        assertThat(service("verso-app").get("networks")).isEqualTo(List.of("backend", "models"));
        for (String name : List.of("postgres", "migrate", "backup")) {
            assertThat(service(name).get("networks")).as(name).isEqualTo(List.of("backend"));
        }
        assertThat(service("ollama").get("networks")).isEqualTo(List.of("models"));
        assertThat(service("edge").get("networks")).isEqualTo(List.of("default", "backend"));
        assertThat(service("keycloak").get("networks")).isEqualTo(List.of("default", "backend"));
        assertThat(environment("verso-app")).containsEntry("VERSO_AI_MODE", "local").containsEntry("VERSO_CHAT_PROVIDER", "ollama");

        Map<String, Object> cloud = new Yaml().load(read("deploy/compose.cloud.yaml"));
        Map<String, Object> cloudApp = (Map<String, Object>) ((Map<String, Object>) cloud.get("services")).get("verso-app");
        assertThat(((Map<String, Object>) cloud.get("services")).keySet()).as("the application and the proxy's mode file")
                .containsExactly("verso-app", "edge");
        assertThat(String.valueOf(((Map<String, Object>) ((Map<String, Object>) cloud.get("services")).get("edge"))
                .get("volumes"))).contains("mode-cloud.conf:/etc/nginx/mode.conf:ro");
        assertThat(String.valueOf(service("edge").get("volumes"))).contains("mode-local.conf:/etc/nginx/mode.conf:ro");
        for (String name : List.of("restore-db", "restore-runner", "restore-flyway")) {
            assertThat(service(name).get("networks")).as(name + ": the restored copy has no route out (review L3)")
                    .isEqualTo(List.of("drill"));
        }
        assertThat((Map<String, Object>) networks.get("drill")).containsEntry("internal", true);
        assertThat((List<String>) cloudApp.get("networks")).containsExactly("backend", "models", "egress");
        assertThat((Map<String, Object>) cloudApp.get("environment")).containsEntry("VERSO_AI_MODE", "cloud");
        assertThat(String.valueOf(cloudApp.get("secrets"))).contains("source=SECRET_CLOUD_API_KEY")
                .contains("target=spring.ai.${VERSO_CLOUD_PROVIDER:-anthropic}.api-key");
        assertThat((Map<String, Object>) ((Map<String, Object>) cloud.get("networks")).get("egress")).doesNotContainKey("internal");
        services().forEach((name, definition) -> assertThat(String.valueOf(((Map<?, ?>) definition).get("secrets")))
                .as(name).doesNotContain("SECRET_CLOUD_API_KEY"));
    }

    /**
     * ADR-0014: the observability stack is opt-in (profile "obs"), on the internal "obs" network; Prometheus also on
     * "backend" (it scrapes the application), Grafana also on "default" (its published port). Alloy reads the Docker
     * socket read-only and has no published port. Grafana does not call home.
     */
    @Test
    @SuppressWarnings("unchecked")
    void observability_whenComposed_isOptInInternalAndQuiet() throws IOException {
        Map<String, Object> networks = (Map<String, Object>) new Yaml().<Map<String, Object>>load(read("compose.yaml"))
                .get("networks");
        assertThat((Map<String, Object>) networks.get("obs")).containsEntry("internal", true);
        for (String name : List.of("prometheus", "alertmanager", "loki", "alloy", "grafana", "obs-edge")) {
            assertThat(service(name).get("profiles")).as(name).isEqualTo(List.of("obs"));
        }
        assertThat(service("prometheus").get("networks")).isEqualTo(List.of("backend", "obs"));
        for (String name : List.of("alertmanager", "loki", "alloy", "grafana")) {
            assertThat(service(name).get("networks")).as(name + ": no route out (review B1)").isEqualTo(List.of("obs"));
        }
        assertThat(service("obs-edge").get("networks")).isEqualTo(List.of("default", "obs"));
        assertThat(read("deploy/obs/edge/nginx.conf")).contains("access_log off;", "error_log /dev/stderr crit;",
                "set $grafana http://grafana:3000;");
        assertThat(read("deploy/obs/alloy/config.alloy")).as("only services with id-only logs (review B2)")
                .contains("regex         = \"verso-app|migrate|edge|backup\"", "action        = \"keep\"");
        assertThat((List<String>) service("alloy").get("volumes")).contains("/var/run/docker.sock:/var/run/docker.sock:ro");
        assertThat(environment("grafana")).containsEntry("GF_ANALYTICS_REPORTING_ENABLED", "false")
                .containsEntry("GF_ANALYTICS_CHECK_FOR_UPDATES", "false").containsEntry("GF_PLUGINS_PREINSTALL_DISABLED", "true")
                .containsEntry("GF_AUTH_ANONYMOUS_ENABLED", "false").containsEntry("GF_SNAPSHOTS_EXTERNAL_ENABLED", "false")
                .containsEntry("GF_PUBLIC_DASHBOARDS_ENABLED", "false").containsEntry("GF_PLUGINS_PLUGIN_ADMIN_ENABLED", "false")
                .containsEntry("GF_USERS_ALLOW_SIGN_UP", "false").containsEntry("GF_NEWS_NEWS_FEED_ENABLED", "false");
        assertThat((List<String>) service("alloy").get("command")).contains("--disable-reporting");
        assertThat(secrets("grafana")).containsExactly("SECRET_GRAFANA_ADMIN_PASSWORD");
        assertThat(read("deploy/obs/prometheus/prometheus.yml")).contains("verso-app:8081", "/actuator/prometheus");
    }

    /** Reference 8.7: every alert has a severity that routes (page | ticket) and a runbook that exists. */
    @Test
    @SuppressWarnings("unchecked")
    void alerts_whenDefined_haveASeverityAndAnExistingRunbook() throws IOException {
        Map<String, Object> rules = new Yaml().load(read("deploy/obs/prometheus/alerts.yml"));
        List<Map<String, Object>> alerts = ((List<Map<String, Object>>) rules.get("groups")).stream()
                .flatMap(g -> ((List<Map<String, Object>>) g.get("rules")).stream()).toList();
        assertThat(alerts).hasSize(6);
        assertThat(ROOT.resolve("deploy/obs/prometheus/alerts.test.yml")).as("promtool unit tests").exists();
        for (Map<String, Object> alert : alerts) {
            String name = String.valueOf(alert.get("alert"));
            assertThat(((Map<String, Object>) alert.get("labels")).get("severity")).as(name).isIn("page", "ticket");
            String runbook = String.valueOf(((Map<String, Object>) alert.get("annotations")).get("runbook"));
            assertThat(ROOT.resolve(runbook)).as(name).exists();
            assertThat(read("docs/runbooks/README.md")).as(name).contains("`" + name + "`");
        }
    }

    /**
     * The edge proxy (ADR-0013; phase 6 reviews B1, B2, L1, F6, F12): its own responses carry the envelope and
     * X-Rag-Mode, it logs neither requests nor client addresses, strips forwarding headers, waits longer than the
     * slowest question and lets the application answer its own 413 up to the multipart limit.
     */
    @Test
    void edgeProxy_whenConfigured_keepsTheContractAndLogsNothing() throws IOException {
        String nginx = read("deploy/edge/nginx.conf");
        assertThat(nginx).contains("access_log off;", "error_log /dev/stderr crit;", "include /etc/nginx/mode.conf;",
                "proxy_intercept_errors off;", "error_page 400 414 494 = @rejected;", "error_page 413 = @too_large;",
                "error_page 500 502 503 504 = @unavailable;", "proxy_set_header Forwarded \"\";",
                "proxy_set_header X-Forwarded-Host \"\";", "proxy_request_buffering off;");
        Matcher named = Pattern.compile("location @(\\w+) \\{([^}]*)}").matcher(nginx);
        int count = 0;
        while (named.find()) {
            count++;
            assertThat(named.group(2)).as("@" + named.group(1)).contains("add_header X-Rag-Mode $rag_mode always;",
                    "\"ok\":false", "default_type application/json;");
        }
        assertThat(count).isEqualTo(3);
        Matcher body = Pattern.compile("client_max_body_size (\\d+)m;").matcher(nginx);
        assertThat(body.find()).isTrue();
        assertThat(Integer.parseInt(body.group(1))).as("above the multipart limit (21MB)").isGreaterThan(21);
        Matcher read = Pattern.compile("proxy_read_timeout (\\d+)s;").matcher(nginx);
        assertThat(read.find()).isTrue();
        assertThat(Integer.parseInt(read.group(1))).as("above the slowest question (ADR-0008)").isGreaterThanOrEqualTo(105);
        assertThat(read("deploy/edge/mode-local.conf")).contains("default local;");
        // ADR-0015: the panel, read-only, with a CSP that allows no inline script and no other origin but the IdP.
        assertThat(String.valueOf(service("edge").get("volumes"))).contains("./panel:/usr/share/verso-panel:ro");
        assertThat(nginx).contains("location /panel/ {", "alias /usr/share/verso-panel/;",
                "add_header Content-Security-Policy \"default-src 'none'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self' http://localhost:8180; form-action 'none'; frame-ancestors 'none'; base-uri 'none'; require-trusted-types-for 'script'; trusted-types 'none'\" always;",
                "add_header X-Content-Type-Options nosniff always;", "absolute_redirect off;");
        assertThat(read("deploy/edge/mode-cloud.conf")).contains("default cloud;");
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
