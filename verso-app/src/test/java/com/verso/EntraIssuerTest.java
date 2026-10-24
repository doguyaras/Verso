package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.platform.security.testing.TestEntraProvider;
import com.verso.platform.security.testing.TestIdentityProvider;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Microsoft Entra ID as a directly trusted issuer next to the bundled Keycloak (ADR-0041), end to end through the
 * application's own config/verso.yml (the OIDC_ENTRA_* variables, as an installation sets them), the real database and
 * the MCP chain: Entra's tokens reach the API with their app roles, Keycloak's are unchanged, and the MCP audience
 * stays separate for both issuers (ADR-0031). The decoder-level matrix is platform-security's TrustedIssuersTest.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "VERSO_MCP_ENABLED=true"})
@VersoTestEnvironment
class EntraIssuerTest {

    private static final TestEntraProvider ENTRA = TestEntraProvider.get();
    private static final String OID = "11111111-2222-4333-8444-555555555555";

    @DynamicPropertySource
    static void entra(DynamicPropertyRegistry registry) {
        registry.add("OIDC_ENTRA_TENANT_ID", () -> TestEntraProvider.TENANT);
        registry.add("OIDC_ENTRA_JWK_SET_URI", ENTRA::jwkSetUri);
        registry.add("OIDC_ENTRA_AUDIENCES", () -> TestEntraProvider.API_AUDIENCE + ",api://verso-api");
        registry.add("OIDC_ENTRA_MCP_AUDIENCES", () -> TestEntraProvider.MCP_AUDIENCE);
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void api_whenEntraAndKeycloakTokensArrive_acceptsBothWithTheirOwnRoleClaims() throws Exception {
        HttpResponse<String> entraAdmin = get("/v1/info", ENTRA.token().roles("verso-admin").build());
        assertThat(entraAdmin.statusCode()).as(entraAdmin.body()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(entraAdmin.body(), "$.roles")).contains("verso-admin", "verso-user");
        HttpResponse<String> entraUser = get("/v1/info", ENTRA.token().build());
        assertThat(JsonPath.<List<String>>read(entraUser.body(), "$.roles")).containsExactly("verso-user");

        HttpResponse<String> keycloak = get("/v1/info", TestIdp.IDP.token().subject("acct-kc").realmRoles("verso-admin").build());
        assertThat(keycloak.statusCode()).isEqualTo(200);
        assertThat(JsonPath.<List<String>>read(keycloak.body(), "$.roles")).contains("verso-admin");
    }

    @Test
    void api_whenAnEntraRuleIsBroken_answers401() throws Exception {
        for (String token : List.of(
                ENTRA.token().tenant(TestEntraProvider.OTHER_TENANT).build(),
                ENTRA.token().version("1.0").build(),
                ENTRA.token().signature(TestEntraProvider.Signature.ES256).build(),
                ENTRA.token().signature(TestEntraProvider.Signature.HS256_WITH_PUBLIC_KEY).build(),
                ENTRA.token().scope(null).build(),
                ENTRA.token().issuer("https://login.microsoftonline.com/common/v2.0").build())) {
            assertThat(get("/v1/info", token).statusCode()).isEqualTo(401);
        }
    }

    @Test
    void mcp_whenTokensCrossSurfaces_refusesThemForBothIssuers() throws Exception {
        String entraMcp = ENTRA.token().audience(TestEntraProvider.MCP_AUDIENCE).build();
        assertThat(postInitialize(entraMcp).statusCode()).isEqualTo(200);
        assertThat(postInitialize(ENTRA.token().build()).statusCode()).as("Entra API token on /mcp").isEqualTo(401);
        assertThat(get("/v1/info", entraMcp).statusCode()).as("Entra MCP token on /v1").isEqualTo(401);

        String keycloakMcp = TestIdp.IDP.token().subject("acct-kc-mcp").audience("verso-mcp").build();
        assertThat(postInitialize(keycloakMcp).statusCode()).isEqualTo(200);
        assertThat(postInitialize(TestIdp.IDP.tokenFor("acct-kc-api")).statusCode()).isEqualTo(401);
        assertThat(get("/v1/info", keycloakMcp).statusCode()).isEqualTo(401);

        HttpResponse<String> metadata = http.send(HttpRequest.newBuilder(
                URI.create(base() + "/.well-known/oauth-protected-resource/mcp")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(JsonPath.<List<String>>read(metadata.body(), "$.authorization_servers"))
                .containsExactlyInAnyOrder(TestIdentityProvider.ISSUER, TestEntraProvider.ISSUER);
    }

    private String base() {
        return "http://localhost:" + port;
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path)).header("Authorization", "Bearer " + token)
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postInitialize(String token) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-11-25\","
                + "\"capabilities\":{},\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}";
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/mcp"))
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(20))
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
