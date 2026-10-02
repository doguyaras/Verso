package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * The demo IdP's realm file (ADR-0010) is security configuration: the token rules the API enforces only hold if the
 * IdP issues matching tokens, and the file is in a public repository. It is parsed (JSON is YAML) and pinned here;
 * scripts/auth-smoke.sh proves the same against a running Keycloak in CI.
 */
class KeycloakRealmTest {

    private static final Path REALM = Path.of("deploy/keycloak/realm/verso-realm.json");
    private static final Path START = Path.of("deploy/keycloak/start.sh");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(VERSO_[A-Z_]+)}");

    @Test
    void realm_whenImported_signsShortLivedTokensWithEs256Only() throws IOException {
        Map<String, Object> realm = realm();
        assertThat(realm.get("defaultSignatureAlgorithm")).isEqualTo("ES256");
        assertThat((Integer) realm.get("accessTokenLifespan")).isBetween(60, 900);
        List<Map<String, Object>> keys = list(map(realm.get("components")).get("org.keycloak.keys.KeyProvider"));
        assertThat(keys).singleElement().satisfies(key -> {
            assertThat(key.get("providerId")).isEqualTo("ecdsa-generated");
            assertThat(map(key.get("config")).get("ecdsaEllipticCurveKey")).isEqualTo(List.of("P-256"));
        });
        assertThat(realm.get("registrationAllowed")).isEqualTo(false);
        assertThat(realm.get("bruteForceProtected")).isEqualTo(true);
        assertThat(realm.get("sslRequired")).isIn("external", "all");
    }

    /** No password grant, no implicit or browser flow; every token is an RFC 9068 access token for verso-api. */
    @Test
    void clients_whenDeclared_useOnlyDeviceFlowOrClientCredentials() throws IOException {
        List<Map<String, Object>> clients = list(realm().get("clients"));
        assertThat(clients).extracting(c -> c.get("clientId")).containsExactlyInAnyOrder("verso-cli", "verso-ci");
        for (Map<String, Object> client : clients) {
            String id = (String) client.get("clientId");
            assertThat(client.get("directAccessGrantsEnabled")).as(id + " password grant").isEqualTo(false);
            assertThat(client.get("implicitFlowEnabled")).as(id + " implicit").isEqualTo(false);
            assertThat(client.get("standardFlowEnabled")).as(id + " authorization code").isEqualTo(false);
            Map<String, Object> attributes = map(client.get("attributes"));
            assertThat(attributes.get("access.token.header.type.rfc9068")).as(id + " at+jwt").isEqualTo("true");
            assertThat(list(client.get("protocolMappers"))).as(id + " audience").anySatisfy(mapper -> {
                assertThat(mapper.get("protocolMapper")).isEqualTo("oidc-audience-mapper");
                assertThat(map(mapper.get("config")).get("included.custom.audience")).isEqualTo("verso-api");
                assertThat(map(mapper.get("config")).get("id.token.claim")).isEqualTo("false");
            });
            if (Boolean.TRUE.equals(client.get("publicClient"))) {
                assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).as(id).isEqualTo("true");
                assertThat(attributes.get("pkce.code.challenge.method")).as(id).isEqualTo("S256");
                assertThat(client.get("serviceAccountsEnabled")).as(id).isEqualTo(false);
            } else {
                assertThat(client.get("serviceAccountsEnabled")).as(id).isEqualTo(true);
                assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).as(id).isNotEqualTo("true");
            }
        }
    }

    /** The repository is public: every secret in the realm is a placeholder that start.sh fills from /run/secrets. */
    @Test
    void secrets_whenRealmIsCommitted_areOnlyPlaceholdersFilledByTheStartScript() throws IOException {
        Map<String, Object> realm = realm();
        for (Map<String, Object> client : list(realm.get("clients"))) {
            if (client.containsKey("secret")) {
                assertThat((String) client.get("secret")).as("client secret").matches(PLACEHOLDER);
            }
        }
        for (Map<String, Object> user : list(realm.get("users"))) {
            for (Map<String, Object> credential : list(user.get("credentials"))) {
                assertThat((String) credential.get("value")).as("user credential").matches(PLACEHOLDER);
                assertThat(credential.get("temporary")).isEqualTo(false);
            }
        }
        String start = Files.readString(VersoPostgres.repoRoot().resolve(START));
        Matcher used = PLACEHOLDER.matcher(Files.readString(VersoPostgres.repoRoot().resolve(REALM)));
        while (used.find()) {
            assertThat(start).as("start.sh exports " + used.group(1))
                    .containsPattern("(?m)^export .*\\b" + used.group(1) + "\\b");
        }
    }

    private static Map<String, Object> realm() throws IOException {
        return new Yaml().load(Files.readString(VersoPostgres.repoRoot().resolve(REALM)));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }
}
