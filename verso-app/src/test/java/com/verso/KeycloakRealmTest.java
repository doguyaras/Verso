package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.VersoPostgres;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.stream.Collectors;
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
                .containsExactlyInAnyOrder("verso-cli", "verso-ci", "verso-panel", "admin-cli", "verso-mcp-inspector");
        for (Map<String, Object> client : clients) {
            if (Boolean.FALSE.equals(client.get("enabled"))) continue;
            String id = (String) client.get("clientId");
            // Its audience comes from the verso-mcp scope, never verso-api (ADR-0031; its own test below).
            if ("verso-mcp-inspector".equals(id)) continue;
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
        // The panel's operator account (user request 2026-10-03): permanent, with its own secret.
        Map<String, Object> admin = list(realm().get("users")).stream().filter(u -> "admin".equals(u.get("username")))
                .findFirst().orElseThrow();
        assertThat(admin.get("realmRoles")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("verso-operator", "verso-admin");
        assertThat(list(admin.get("credentials")).getFirst().get("value")).isEqualTo("${VERSO_PANEL_ADMIN_PASSWORD}");
    }

    /**
     * ADR-0019: the API reads roles from realm_access.roles and groups from "groups" (the defaults of
     * verso.security.authorities). Every client a person signs in with puts both into the access token only, so the
     * panel and the command line get the same rights; the demo admin is the only verso-admin, the demo user a
     * verso-user.
     */
    @Test
    void userClients_whenImported_carryRolesAndGroupsInTheAccessTokenOnly() throws IOException {
        for (String id : List.of("verso-panel", "verso-cli")) {
            List<Map<String, Object>> mappers = list(client(id).get("protocolMappers"));
            assertThat(mappers).as(id + " realm roles").anySatisfy(m -> {
                assertThat(m.get("protocolMapper")).isEqualTo("oidc-usermodel-realm-role-mapper");
                assertThat(map(m.get("config")).get("claim.name")).isEqualTo("realm_access.roles");
                assertThat(map(m.get("config")).get("access.token.claim")).isEqualTo("true");
                assertThat(map(m.get("config")).get("id.token.claim")).isEqualTo("false");
            });
            assertThat(mappers).as(id + " groups").anySatisfy(m -> {
                assertThat(m.get("protocolMapper")).isEqualTo("oidc-group-membership-mapper");
                assertThat(map(m.get("config")).get("claim.name")).isEqualTo("groups");
                // Full paths: two subgroups with the same name under different parents stay distinct.
                assertThat(map(m.get("config")).get("full.path")).isEqualTo("true");
                assertThat(map(m.get("config")).get("access.token.claim")).isEqualTo("true");
                assertThat(map(m.get("config")).get("id.token.claim")).isEqualTo("false");
                assertThat(map(m.get("config")).get("userinfo.token.claim")).isEqualTo("false");
            });
        }
        assertThat(list(map(realm().get("roles")).get("realm"))).extracting(r -> r.get("name"))
                .contains("verso-admin", "verso-user");
        Map<String, List<String>> rolesByUser = new java.util.HashMap<>();
        for (Map<String, Object> user : list(realm().get("users"))) {
            @SuppressWarnings("unchecked")
            List<String> roles = (List<String>) user.get("realmRoles");
            rolesByUser.put((String) user.get("username"), roles);
        }
        assertThat(rolesByUser.get("admin")).contains("verso-admin");
        assertThat(rolesByUser.get("demo")).contains("verso-user").doesNotContain("verso-admin");
        assertThat(rolesByUser).as("only the admin account is a verso-admin")
                .allSatisfy((name, roles) -> assertThat(roles.contains("verso-admin")).isEqualTo("admin".equals(name)));
    }

    /**
     * ADR-0031: the MCP client's tokens are for /mcp only. The verso-mcp scope adds the audience verso-mcp and the
     * groups (full path, access token only); the client has no verso-api mapper and no role scope (fullScopeAllowed
     * off: an MCP token never carries verso-admin), a code flow with PKCE S256 to exact local redirects, and a consent
     * screen: the user sees that an AI client is about to read their documents.
     */
    @Test
    void mcpClient_whenImported_getsOnlyTheMcpAudienceWithPkceAndConsent() throws IOException {
        Map<String, Object> scope = list(realm().get("clientScopes")).stream()
                .filter(s -> "verso-mcp".equals(s.get("name"))).findFirst().orElseThrow();
        List<Map<String, Object>> scopeMappers = list(scope.get("protocolMappers"));
        assertThat(scopeMappers).extracting(m -> m.get("protocolMapper"))
                .containsExactlyInAnyOrder("oidc-audience-mapper", "oidc-group-membership-mapper");
        assertThat(scopeMappers).anySatisfy(m -> {
            assertThat(map(m.get("config")).get("included.custom.audience")).isEqualTo("verso-mcp");
            assertThat(map(m.get("config")).get("access.token.claim")).isEqualTo("true");
            assertThat(map(m.get("config")).get("id.token.claim")).isEqualTo("false");
        });
        assertThat(list(realm().get("clientScopes"))).extracting(s -> s.get("name")).contains("basic");
        Map<String, Object> client = client("verso-mcp-inspector");
        Map<String, Object> attributes = map(client.get("attributes"));
        assertThat(client.get("publicClient")).isEqualTo(true);
        assertThat(client.get("standardFlowEnabled")).isEqualTo(true);
        assertThat(client.get("implicitFlowEnabled")).isEqualTo(false);
        assertThat(client.get("directAccessGrantsEnabled")).isEqualTo(false);
        assertThat(client.get("serviceAccountsEnabled")).isEqualTo(false);
        assertThat(client.get("fullScopeAllowed")).isEqualTo(false);
        assertThat(client.get("consentRequired")).isEqualTo(true);
        assertThat(attributes.get("pkce.code.challenge.method")).isEqualTo("S256");
        assertThat(attributes.get("access.token.header.type.rfc9068")).isEqualTo("true");
        assertThat(attributes.get("oauth2.device.authorization.grant.enabled")).isEqualTo("false");
        assertThat(client.get("defaultClientScopes")).isEqualTo(List.of("basic", "verso-mcp"));
        assertThat(client.get("optionalClientScopes")).isEqualTo(List.of());
        assertThat(list(client.get("protocolMappers"))).isEmpty();
        assertThat(client.get("redirectUris")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .allSatisfy(uri -> assertThat((String) uri).startsWith("http://localhost:6274/").doesNotContain("*"));
    }

    /**
     * ADR-0031: a realm file that declares clientScopes replaces every scope Keycloak would otherwise create on import.
     * The file therefore carries Keycloak 26.8.1's whole built-in set and its realm defaults, exactly as an import
     * without clientScopes produced them (compared through the admin API: same scopes, same client assignments, same
     * panel and CI token claims, same CORS answer; docs/evidence/mcp-sunucusu-dogrulama.md). The panel's CORS needs
     * web-origins in the realm, the service-account client service_account; verso-mcp is the only addition.
     */
    @Test
    void clientScopes_whenDeclared_keepKeycloaksBuiltInSetAndDefaults() throws IOException {
        Map<String, Object> realm = realm();
        assertThat(list(realm.get("clientScopes"))).extracting(s -> s.get("name")).containsExactlyInAnyOrder(
                "AuthnContextClassRef", "acr", "address", "basic", "email", "microprofile-jwt", "offline_access",
                "organization", "phone", "profile", "role_list", "roles", "saml_organization", "service_account",
                "web-origins", "verso-mcp");
        assertThat(realm.get("defaultDefaultClientScopes")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("AuthnContextClassRef", "acr", "basic", "email", "profile", "role_list",
                        "roles", "saml_organization", "web-origins");
        assertThat(realm.get("defaultOptionalClientScopes")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .containsExactlyInAnyOrder("address", "microprofile-jwt", "offline_access", "organization", "phone");
        Map<String, Object> webOrigins = list(realm.get("clientScopes")).stream()
                .filter(s -> "web-origins".equals(s.get("name"))).findFirst().orElseThrow();
        assertThat(list(webOrigins.get("protocolMappers"))).extracting(m -> m.get("protocolMapper"))
                .contains("oidc-allowed-origins-mapper");
        Map<String, Object> roles = list(realm.get("clientScopes")).stream()
                .filter(s -> "roles".equals(s.get("name"))).findFirst().orElseThrow();
        assertThat(list(roles.get("protocolMappers"))).extracting(m -> m.get("protocolMapper"))
                .contains("oidc-usermodel-realm-role-mapper");
        // No client's default scope may point at a scope the realm lacks (Keycloak drops it silently).
        Set<Object> declared = list(realm.get("clientScopes")).stream().map(s -> s.get("name")).collect(Collectors.toSet());
        for (Map<String, Object> client : list(realm.get("clients"))) {
            Object scopes = client.get("defaultClientScopes");
            if (scopes != null) assertThat(declared).as(client.get("clientId") + " scopes").containsAll(list2(scopes));
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list2(Object value) {
        return (List<Object>) value;
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
