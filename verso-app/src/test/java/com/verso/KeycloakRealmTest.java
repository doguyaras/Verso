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

    /** Login attempts and sessions are bounded; no self-service registration or password reset (T7). */
    @Test
    void realm_whenImported_limitsLoginAttemptsAndSessions() throws IOException {
        Map<String, Object> realm = realm();
        assertThat(realm.get("resetPasswordAllowed")).isEqualTo(false);
        assertThat(realm.get("rememberMe")).isEqualTo(false);
        assertThat((Integer) realm.get("failureFactor")).isBetween(1, 10);
        assertThat((Integer) realm.get("maxFailureWaitSeconds")).isGreaterThanOrEqualTo(300);
        assertThat(realm.get("revokeRefreshToken")).isEqualTo(true);
        assertThat(realm.get("refreshTokenMaxReuse")).isEqualTo(0);
        assertThat((Integer) realm.get("ssoSessionIdleTimeout")).isLessThanOrEqualTo(3600);
        assertThat((Integer) realm.get("ssoSessionMaxLifespan")).isLessThanOrEqualTo(36000);
        assertThat((Integer) realm.get("oauth2DeviceCodeLifespan")).isLessThanOrEqualTo(600);
    }

    /**
     * Phase 3 security review: Keycloak's built-in admin-cli client of every realm allows the password grant; here it
     * is disabled. The CLI client shows a consent screen (device-code phishing) and cannot ask for offline tokens.
     */
    @Test
    void builtInAndCliClients_whenImported_allowNoPasswordGrantOrOfflineTokens() throws IOException {
        Map<String, Object> adminCli = client("admin-cli");
        assertThat(adminCli.get("enabled")).isEqualTo(false);
        assertThat(adminCli.get("directAccessGrantsEnabled")).isEqualTo(false);
        Map<String, Object> cli = client("verso-cli");
        assertThat(cli.get("consentRequired")).isEqualTo(true);
        assertThat(cli.get("optionalClientScopes")).isEqualTo(List.of());
        assertThat(cli.get("defaultClientScopes")).isEqualTo(List.of("basic"));
    }

    /**
     * No password grant and no implicit flow; every token is an RFC 9068 access token for verso-api. The browser flow
     * (authorization code + PKCE) belongs to the panel's client alone (ADR-0015).
     */
    @Test
    void clients_whenDeclared_useOnlyDeviceFlowClientCredentialsOrThePanelsCodeFlow() throws IOException {
        List<Map<String, Object>> clients = list(realm().get("clients"));
        assertThat(clients).extracting(c -> c.get("clientId"))
                .containsExactlyInAnyOrder("verso-cli", "verso-ci", "verso-panel", "admin-cli");
        for (Map<String, Object> client : clients) {
            if (Boolean.FALSE.equals(client.get("enabled"))) continue;
            String id = (String) client.get("clientId");
            assertThat(client.get("directAccessGrantsEnabled")).as(id + " password grant").isEqualTo(false);
            assertThat(client.get("implicitFlowEnabled")).as(id + " implicit").isEqualTo(false);
            assertThat(client.get("standardFlowEnabled")).as(id + " authorization code")
                    .isEqualTo("verso-panel".equals(id));
            Map<String, Object> attributes = map(client.get("attributes"));
            assertThat(attributes.get("access.token.header.type.rfc9068")).as(id + " at+jwt").isEqualTo("true");
            assertThat(list(client.get("protocolMappers"))).as(id + " audience").anySatisfy(mapper -> {
                assertThat(mapper.get("protocolMapper")).isEqualTo("oidc-audience-mapper");
                assertThat(map(mapper.get("config")).get("included.custom.audience")).isEqualTo("verso-api");
                assertThat(map(mapper.get("config")).get("id.token.claim")).isEqualTo("false");
            });
            if ("verso-panel".equals(id)) {
                assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).as(id).isEqualTo("false");
                assertThat(attributes.get("pkce.code.challenge.method")).as(id).isEqualTo("S256");
                assertThat(client.get("serviceAccountsEnabled")).as(id).isEqualTo(false);
            } else if (Boolean.TRUE.equals(client.get("publicClient"))) {
                assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).as(id).isEqualTo("true");
                assertThat(attributes.get("pkce.code.challenge.method")).as(id).isEqualTo("S256");
                assertThat(client.get("serviceAccountsEnabled")).as(id).isEqualTo(false);
            } else {
                assertThat(client.get("serviceAccountsEnabled")).as(id).isEqualTo(true);
                assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).as(id).isNotEqualTo("true");
            }
        }
    }

    /**
     * ADR-0015: the panel's client redirects to one exact URL (no wildcard), talks only to its own origin, puts the
     * roles into the access token for the role matrix and the user name only into the ID token, which stays in the
     * browser; the demo user is an operator, so the demo shows every screen.
     */
    @Test
    void panelClient_whenImported_isAnExactPkceBrowserClient() throws IOException {
        Map<String, Object> panel = client("verso-panel");
        assertThat(panel.get("publicClient")).isEqualTo(true);
        assertThat(panel.get("implicitFlowEnabled")).isEqualTo(false);
        assertThat(panel.get("directAccessGrantsEnabled")).isEqualTo(false);
        assertThat(panel.get("consentRequired")).isEqualTo(false);
        assertThat(map(panel.get("attributes")).get("pkce.code.challenge.method")).isEqualTo("S256");
        assertThat(panel.get("redirectUris")).isEqualTo(List.of("http://localhost:8080/panel/"));
        assertThat(panel.get("webOrigins")).isEqualTo(List.of("http://localhost:8080"));
        assertThat(map(panel.get("attributes")).get("post.logout.redirect.uris")).isEqualTo("http://localhost:8080/panel/");
        assertThat(panel.get("defaultClientScopes")).isEqualTo(List.of("basic"));
        assertThat(panel.get("optionalClientScopes")).isEqualTo(List.of());
        List<Map<String, Object>> mappers = list(panel.get("protocolMappers"));
        assertThat(mappers).anySatisfy(m -> {
            assertThat(m.get("protocolMapper")).isEqualTo("oidc-usermodel-realm-role-mapper");
            assertThat(map(m.get("config")).get("access.token.claim")).isEqualTo("true");
            assertThat(map(m.get("config")).get("id.token.claim")).as("roles stay out of the ID token").isEqualTo("false");
        });
        assertThat(list(map(realm().get("roles")).get("realm"))).anySatisfy(r -> assertThat(r.get("name")).isEqualTo("verso-operator"));
        assertThat(mappers).anySatisfy(m -> {
            assertThat(map(m.get("config")).get("claim.name")).isEqualTo("preferred_username");
            assertThat(map(m.get("config")).get("access.token.claim")).as("no name in the access token").isEqualTo("false");
        });
        Map<String, Object> demo = list(realm().get("users")).stream().filter(u -> "demo".equals(u.get("username")))
                .findFirst().orElseThrow();
        assertThat(demo.get("realmRoles")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .contains("verso-operator");
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

    private static Map<String, Object> client(String clientId) throws IOException {
        return list(realm().get("clients")).stream().filter(c -> clientId.equals(c.get("clientId"))).findFirst()
                .orElseThrow(() -> new AssertionError("client " + clientId + " not in the realm file"));
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
