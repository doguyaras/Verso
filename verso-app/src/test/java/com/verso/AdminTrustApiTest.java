package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * GET /v1/admin/trust (ADR-0050) in local mode: verso-admin only (ADR-0019), not cached, the local-mode facts, and no
 * secret anywhere in the answer: not a configured secret value, not the content of a SECRET_ variable.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class AdminTrustApiTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    ConfigurableEnvironment environment;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String admin = TestIdp.IDP.token().subject("acct-trust-admin").realmRoles("verso-admin").build();

    /** ADR-0019: a user, an operator and a caller with a foreign role get 403 ACCESS_DENIED, anonymous 401. */
    @Test
    void trust_whenCallerIsNotAnAdmin_isDenied() throws Exception {
        String operator = TestIdp.IDP.token().subject("acct-trust-op").realmRoles("verso-operator", "verso-user").build();
        String user = TestIdp.IDP.tokenFor("acct-trust-" + UUID.randomUUID());
        String other = TestIdp.IDP.token().subject("acct-trust-other").realmRoles("some-other-role").build();
        for (String token : List.of(user, operator, other)) {
            HttpResponse<String> denied = get("Bearer " + token);
            assertThat(denied.statusCode()).isEqualTo(403);
            assertThat(denied.body()).contains("\"code\":90101").doesNotContain("chatModel");
        }
        assertThat(get(null).statusCode()).isEqualTo(401);
        assertThat(get("Bearer not-a-token").statusCode()).isEqualTo(401);
    }

    @Test
    void trust_forAdminInLocalMode_statesTheLocalFactsAndIsNotCached() throws Exception {
        HttpResponse<String> response = get("Bearer " + admin);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("local");
        assertThat(JsonPath.<String>read(response.body(), "$.ai.mode")).isEqualTo("local");
        assertThat(JsonPath.<String>read(response.body(), "$.ai.chatModel")).isNotBlank();
        assertThat(JsonPath.<String>read(response.body(), "$.ai.embeddingModel")).isNotBlank();
        assertThat(JsonPath.<Boolean>read(response.body(), "$.egress.externalConnectorEgress")).isFalse();
        assertThat(JsonPath.<List<String>>read(response.body(), "$.egress.allowedHosts")).isEmpty();
        assertThat(JsonPath.<Boolean>read(response.body(), "$.connectors.cloudConnectorPresent")).isFalse();
        assertThat(JsonPath.<Boolean>read(response.body(), "$.connectors.known")).isTrue();
        assertThat(JsonPath.<Boolean>read(response.body(), "$.mcp.enabled")).isFalse();
        assertThat(JsonPath.<List<String>>read(response.body(), "$.identity.issuers[*].name")).isNotEmpty();
        assertThat(JsonPath.<String>read(response.body(), "$.guarantee")).startsWith("Yerel mod:")
                .contains("bu sunucudan çıkmaz");
        assertThat(JsonPath.<String>read(response.body(), "$.backup.verification")).isEqualTo("kurulum betiğiyle doğrulanır");
        assertThat(JsonPath.<Object>read(response.body(), "$.backup.lastSuccessAt")).isNull();
        assertThat(JsonPath.<String>read(response.body(), "$.generatedAt")).isNotBlank();
    }

    /** Nothing configured as a secret may appear, whatever the field: values of secret-named properties and SECRET_*. */
    @Test
    void trust_neverContainsASecretValue() throws Exception {
        String body = get("Bearer " + admin).body();
        for (var source : environment.getPropertySources()) {
            if (!(source instanceof EnumerablePropertySource<?> enumerable)) continue;
            for (String name : enumerable.getPropertyNames()) {
                String upper = name.toUpperCase(java.util.Locale.ROOT);
                boolean secret = upper.startsWith("SECRET_") || upper.contains("PASSWORD") || upper.contains("API-KEY")
                        || upper.contains("API_KEY") || upper.contains("CLIENT-SECRET") || upper.contains("TOKEN-VALUE");
                Object value = secret ? enumerable.getProperty(name) : null;
                if (value instanceof String text && text.length() >= 6) {
                    assertThat(body).as("value of %s", name).doesNotContain(text);
                }
            }
        }
        assertThat(body).doesNotContainIgnoringCase("password").doesNotContainIgnoringCase("secret")
                .doesNotContainIgnoringCase("jwk").doesNotContain("credentialRef");
    }

    private HttpResponse<String> get(String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/admin/trust"));
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
