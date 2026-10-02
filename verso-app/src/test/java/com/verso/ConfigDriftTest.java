package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.FileSystemResource;

/**
 * Catches drift between the local yml and the deploy config (reference 15.2, 19.5):
 * <ol>
 *   <li>Security and connection keys (rate-limit scopes, client base URLs, internal-access) form the SAME key set in
 *       application-local.yml and config/verso.yml.</li>
 *   <li>Every ${ENV_VAR} placeholder in config/verso.yml is declared in the deploy env template.</li>
 *   <li>Secret keys carry no literal fallback (${X:value}). Scope: config/verso.yml only; application*.yml and
 *       deploy/*.env* are covered by scripts/config-lint.js in CI and in the pre-commit hook.</li>
 * </ol>
 * Paths are relative to the module root; the deploy template sits at the repository root.
 *
 * <p>Adapted from the reference blueprint template (tests/ConfigDriftTest.java).
 */
class ConfigDriftTest {

    private static final Path LOCAL = Path.of("src/main/resources/application-local.yml");
    private static final Path SERVICE = Path.of("src/main/resources/config/verso.yml");
    /** Deploy template at the repository root; the root is found by walking up to the deploy/ directory. */
    private static final Path ENV_TEMPLATE = repoRoot().resolve("deploy/prod.env.example");

    /**
     * Prefixes whose KEY sets must be identical in both files (values may differ: localhost vs ${ENV}). Only keys
     * that change per environment belong here: environment-independent business config lives once in
     * config/verso.yml and must not be copied into the local file (reference 15.2; second-round environment review
     * L-C). The first four come from the blueprint; the Verso ones activate when phases 2-6 add those keys.
     */
    private static final Set<String> MIRRORED_KEY_PREFIXES = Set.of(
            "service-jwt.internal-access",
            "rate-limit.rules",
            "spring.http.serviceclient",
            "services.",
            "spring.datasource.url",
            "spring.datasource.username",
            "spring.flyway.url",
            "spring.flyway.user",
            "spring.ai.ollama.base-url",
            "spring.security.oauth2.resourceserver.jwt.");

    /** Security prefixes whose keys AND values must match (allowlist paths and actors). */
    private static final Set<String> MIRRORED_VALUE_PREFIXES = Set.of("service-jwt.internal-access");

    /** Key fragments for which a fallback is forbidden. */
    private static final Pattern SECRET_KEY =
            Pattern.compile("(secret|password|pass|token|key|credential)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Z0-9_]+)(:[^}]*)?}");

    @Test
    void mirroredKeys_whenLocalAndDeployCompared_areIdentical() {
        Properties local = load(LOCAL);
        Properties service = load(SERVICE);
        for (String prefix : MIRRORED_KEY_PREFIXES) {
            boolean withValues = MIRRORED_VALUE_PREFIXES.contains(prefix);
            Set<String> l = keysWithPrefix(local, prefix, withValues);
            Set<String> s = keysWithPrefix(service, prefix, withValues);
            assertThat(l).as("prefix '%s': application-local.yml <-> config/verso.yml %s set",
                            prefix, withValues ? "key+value" : "key")
                    .containsExactlyInAnyOrderElementsOf(s);
        }
    }

    @Test
    void placeholders_whenUsedInServiceConfig_existInDeployEnvTemplate() throws IOException {
        Set<String> declared = Files.readAllLines(ENV_TEMPLATE).stream()
                .map(String::trim)
                .filter(l -> !l.isEmpty() && !l.startsWith("#") && l.contains("="))
                .map(l -> l.substring(0, l.indexOf('=')).trim())
                .collect(Collectors.toSet());
        Set<String> used = placeholders(Files.readString(SERVICE));
        // Values from /run/secrets are a config tree, not env variables; they are not looked up in the template.
        used.removeIf(v -> v.startsWith("SECRET_"));
        assertThat(declared).as("variables missing from the deploy env template").containsAll(used);
    }

    @Test
    void secretKeys_whenConfigured_haveNoLiteralFallback() {
        Properties service = load(SERVICE);
        Set<String> offenders = new TreeSet<>();
        for (String key : keys(service)) {
            if (!SECRET_KEY.matcher(key).find()) continue;
            Matcher m = PLACEHOLDER.matcher(String.valueOf(service.get(key)));
            while (m.find()) {
                if (m.group(2) != null) offenders.add(key + " = " + m.group());
            }
        }
        assertThat(offenders).as("literal fallback on secret keys is forbidden (fail-fast)").isEmpty();
    }

    private static Properties load(Path p) {
        YamlPropertiesFactoryBean f = new YamlPropertiesFactoryBean();
        f.setResources(new FileSystemResource(p));
        Properties props = f.getObject();
        assertThat(props).as("yml could not be read: %s", p).isNotNull();
        return props;
    }

    private static Set<String> keysWithPrefix(Properties props, String prefix, boolean withValues) {
        return keys(props).stream()
                .filter(k -> k.startsWith(prefix))
                // compare sets, not order, for lists such as internal-access[0].path
                .map(k -> withValues ? k + "=" + String.valueOf(props.get(k)).replaceAll("\\s+", "") : k)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    /**
     * TRAP: Properties.stringPropertyNames() only returns String-valued entries; YamlPropertiesFactoryBean stores
     * numbers (limit: 60) as Integer, so those keys would silently disappear and the drift test would catch nothing.
     * Hence keySet().
     */
    private static Set<String> keys(Properties props) {
        return props.keySet().stream().map(String::valueOf).collect(Collectors.toCollection(TreeSet::new));
    }

    /** Walks up from the module directory to the directory that contains deploy/ (the repository root). */
    private static Path repoRoot() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("deploy"))) return dir;
            dir = dir.getParent();
        }
        throw new IllegalStateException("deploy/ not found at the repository root; adjust ENV_TEMPLATE");
    }

    private static Set<String> placeholders(String text) {
        Set<String> out = new TreeSet<>();
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) out.add(m.group(1));
        return out;
    }
}
