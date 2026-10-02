package com.verso.platform.security;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.platform.security.testing.TestIdentityProvider;
import com.verso.platform.security.testing.TestIdentityProvider.Signature;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The resource server end to end on a real port (reference 9.2, 6.5, 7.6; ADR-0005): the production decoder fetches
 * the keys over HTTP from the test identity provider and validates every token. Each refused token answers 401 in
 * the common envelope with WWW-Authenticate, and no token ever reaches a log.
 */
@SpringBootTest(classes = PlatformSecurityTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ContextConfiguration(initializers = PlatformSecurityTest.Idp.class)
class PlatformSecurityTest {

    static final TestIdentityProvider IDP = TestIdentityProvider.get();

    static final class Idp implements ApplicationContextInitializer<ConfigurableApplicationContext> {
        @Override
        public void initialize(ConfigurableApplicationContext context) {
            TestPropertyValues.of(IDP.propertyPairs()).applyTo(context);
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    static class App {
        @Bean
        Probe probe() {
            return new Probe();
        }
    }

    @RestController
    static class Probe {
        @GetMapping("/v1/probe/me")
        String me(@CurrentAccount AccountId account) {
            return account.value();
        }

        /** A future @PreAuthorize denial: must stay 403, not become the global handler's 500. */
        @GetMapping("/v1/probe/denied")
        String denied() {
            throw new AccessDeniedException("probe denies");
        }
    }

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detach() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void request_whenTokenIsValid_reachesTheControllerWithTheSubjectAsAccount() throws Exception {
        HttpResponse<String> response = get("/v1/probe/me", "Bearer " + IDP.tokenFor("acct-7f3a"));
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("acct-7f3a");
        assertThat(IDP.jwksRequests()).as("keys fetched from the JWKS URL").isPositive();
    }

    @Test
    void request_whenNoToken_isRejectedWith401EnvelopeWithoutErrorCode() throws Exception {
        HttpResponse<String> response = get("/v1/probe/me", null);
        assertUnauthenticated(response);
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer");
    }

    /** Every token the API must refuse, each with its own reason (reference 9.2, RFC 8725). */
    @Test
    void request_whenTokenIsRefused_isRejectedWith401InvalidToken() throws Exception {
        Map<String, String> refused = Map.ofEntries(
                Map.entry("RS256 although its key is published", IDP.token().signature(Signature.RS256).build()),
                Map.entry("HS256 signed with the public key (alg confusion)",
                        IDP.token().signature(Signature.HS256_WITH_PUBLIC_KEY).build()),
                Map.entry("alg none", IDP.token().signature(Signature.NONE).build()),
                Map.entry("key not in the JWKS", IDP.token().signature(Signature.ES256_UNKNOWN_KEY).build()),
                Map.entry("other issuer", IDP.token().issuer("https://evil.test.invalid/realms/verso").build()),
                Map.entry("issuer with a trailing slash", IDP.token().issuer(TestIdentityProvider.ISSUER + "/").build()),
                Map.entry("ID token audience", IDP.token().audience("verso-cli").build()),
                Map.entry("expired beyond the clock skew", IDP.token().expiresAt(Instant.now().minusSeconds(120)).build()),
                Map.entry("not valid yet", IDP.token().notBefore(Instant.now().plusSeconds(600)).build()),
                Map.entry("no exp", IDP.token().expiresAt(null).build()),
                Map.entry("no sub", IDP.token().subject(null).build()),
                Map.entry("sub with a path separator", IDP.token().subject("acct/../other").build()),
                Map.entry("typ JWT instead of at+jwt (an ID token header)", IDP.token().typ("JWT").build()),
                Map.entry("no typ header", IDP.token().typ(null).build()),
                Map.entry("not a JWT", "abc.def.ghi"));
        List<String> tokens = new ArrayList<>();
        for (Map.Entry<String, String> entry : refused.entrySet()) {
            tokens.add(entry.getValue());
            HttpResponse<String> response = get("/v1/probe/me", "Bearer " + entry.getValue());
            assertThat(response.statusCode()).as(entry.getKey()).isEqualTo(401);
            assertThat(response.headers().firstValue("WWW-Authenticate")).as(entry.getKey())
                    .hasValue("Bearer error=\"invalid_token\"");
            assertThat(response.body()).as(entry.getKey()).contains("\"code\":90100").doesNotContain("acct");
        }
        assertLogsFreeOf(tokens);
    }

    @Test
    void request_whenTokenOutsideTheAuthorizationHeader_isNotAccepted() throws Exception {
        String token = IDP.tokenFor("acct-query");
        assertUnauthenticated(get("/v1/probe/me?access_token=" + token, null));
        assertUnauthenticated(get("/v1/probe/me", "Basic " + token));
        assertLogsFreeOf(List.of(token));
    }

    @Test
    void request_whenControllerDeniesAccess_isRejectedWith403EnvelopeNot500() throws Exception {
        HttpResponse<String> response = get("/v1/probe/denied", "Bearer " + IDP.tokenFor("acct-denied"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":90101");
        assertThat(appender.list).noneMatch(e -> e.getLevel().toString().equals("ERROR"));
    }

    @Test
    void validToken_whenUsed_neverReachesTheLogs() throws Exception {
        String token = IDP.tokenFor("acct-log");
        get("/v1/probe/me", "Bearer " + token);
        get("/v1/does-not-exist", "Bearer " + token);
        assertLogsFreeOf(List.of(token));
    }

    @Test
    void startup_whenResourceServerConfigured_generatesNoDefaultUserPassword() throws Exception {
        // A JwtDecoder bean makes Boot's UserDetailsServiceAutoConfiguration back off; nothing is printed or usable.
        HttpResponse<String> response = get("/v1/probe/me", "Basic " + java.util.Base64.getEncoder()
                .encodeToString("user:password".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertUnauthenticated(response);
    }

    private void assertUnauthenticated(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(401);
        assertThat(response.headers().allValues(TraceIds.HEADER)).hasSize(1);
        assertThat(response.body()).contains("\"ok\":false").contains("\"code\":90100").contains("\"service\":\"security\"");
    }

    private void assertLogsFreeOf(List<String> tokens) {
        for (ILoggingEvent event : appender.list) {
            String line = event.getFormattedMessage() + " " + String.valueOf(event.getThrowableProxy() == null ? ""
                    : event.getThrowableProxy().getMessage());
            for (String token : tokens) {
                // The signature part alone identifies a token; header and payload are base64 of public data.
                String signature = token.substring(token.lastIndexOf('.') + 1);
                if (signature.length() > 8) assertThat(line).as(event.getLoggerName()).doesNotContain(signature);
                assertThat(line).doesNotContain(token);
            }
        }
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
