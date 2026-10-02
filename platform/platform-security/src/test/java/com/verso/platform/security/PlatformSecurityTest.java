package com.verso.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationDeniedException;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

        /** No @CurrentAccount: only the filter chain protects it. */
        @GetMapping("/v1/probe/open")
        String open() {
            return "reached";
        }

        /** What @PreAuthorize throws: a subclass of AccessDeniedException (phase 3 test review T1). */
        @GetMapping("/v1/probe/denied-by-rule")
        String deniedByRule() {
            throw new AuthorizationDeniedException("probe denies", new AuthorizationDecision(false));
        }

        /** A future @PreAuthorize denial: must stay 403, not become the global handler's 500. */
        @GetMapping("/v1/probe/denied")
        String denied() {
            throw new AccessDeniedException("probe denies");
        }
    }

    /** A module's own chain for its own paths, in front of the API chain (phase 3 reviews C1/S4). */
    @Configuration(proxyBeanMethods = false)
    static class ExtraChain {
        @Bean
        @Order(0)
        SecurityFilterChain hooks(HttpSecurity http) throws Exception {
            return http.securityMatcher("/hooks/**").authorizeHttpRequests(r -> r.anyRequest().permitAll()).build();
        }
    }

    /** A controller that asks for the account as a String: must never start (phase 3 review S1). */
    @RestController
    static class WrongAccountType {
        @GetMapping("/v1/probe/wrong")
        String wrong(@CurrentAccount @RequestParam(required = false) String account) {
            return "bound=" + account;
        }
    }

    @Value("${local.server.port}")
    int port;

    @Autowired
    ApplicationContext context;

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
            assertThat(response.body()).as(entry.getKey()).contains("\"code\":90100").doesNotContain("account-1");
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
        assertThat(response.headers().firstValue("WWW-Authenticate")).hasValue("Bearer error=\"insufficient_scope\"");
        assertThat(appender.list).noneMatch(e -> e.getLevel().toString().equals("ERROR"));
    }

    /** @PreAuthorize throws AuthorizationDeniedException; the superclass walk must keep it a 403 (T1). */
    @Test
    void request_whenControllerThrowsAuthorizationDeniedException_isRejectedWith403EnvelopeNot500() throws Exception {
        HttpResponse<String> response = get("/v1/probe/denied-by-rule", "Bearer " + IDP.tokenFor("acct-rule"));
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(response.body()).contains("\"code\":90101");
    }

    /** Stateless: neither an accepted nor a rejected request creates a session (T4). */
    @Test
    void responses_whenAuthenticatedOrRejected_setNoSessionCookie() throws Exception {
        assertThat(get("/v1/probe/me", "Bearer " + IDP.tokenFor("acct-cookie")).headers().allValues("Set-Cookie")).isEmpty();
        assertThat(get("/v1/probe/me", null).headers().allValues("Set-Cookie")).isEmpty();
    }

    /** RFC 6750 2.2 form-encoded body tokens are not accepted either (T9). */
    @Test
    void request_whenTokenInFormBody_isNotAccepted() throws Exception {
        String token = IDP.tokenFor("acct-form");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/probe/me"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString("access_token=" + token))
                .timeout(Duration.ofSeconds(10)).build(), HttpResponse.BodyHandlers.ofString());
        assertUnauthenticated(response);
        assertLogsFreeOf(List.of(token));
    }

    /** Made-up key ids must not make the API fetch the JWKS once per request (phase 3 reviews C3/S2/R1). */
    @Test
    void request_whenManyTokensCarryUnknownKeyIds_fetchesTheKeysAtMostOnce() throws Exception {
        get("/v1/probe/me", "Bearer " + IDP.tokenFor("acct-warm")); // keys cached
        int before = IDP.jwksRequests();
        for (int i = 0; i < 20; i++) {
            String token = IDP.token().signature(Signature.ES256_UNKNOWN_KEY).keyId("made-up-" + i).build();
            assertThat(get("/v1/probe/me", "Bearer " + token).statusCode()).as("made-up kid " + i).isEqualTo(401);
        }
        assertThat(IDP.jwksRequests() - before).isLessThanOrEqualTo(1);
        assertThat(get("/v1/probe/me", "Bearer " + IDP.tokenFor("acct-after")).statusCode())
                .as("valid tokens still pass").isEqualTo(200);
    }

    /** Keys unreachable and nothing cached: 503 with Retry-After, not a 500 and not the caller's 401 (R2/S3). */
    @Test
    void request_whenIdpKeysAreUnreachable_isRejectedWith503() throws Exception {
        try (ConfigurableApplicationContext down = start(new Class<?>[]{App.class},
                "verso.security.jwt.jwk-set-uri=http://127.0.0.1:1/jwks")) {
            attach(); // the second application re-initialised logback, which dropped the appender
            String token = IDP.tokenFor("acct-down");
            HttpResponse<String> response = get(port(down), "/v1/probe/me", "Bearer " + token);
            assertThat(response.statusCode()).isEqualTo(503);
            assertThat(response.body()).contains("\"code\":90103");
            assertThat(response.headers().firstValue("Retry-After")).hasValue("30");
            assertThat(response.headers().firstValue("WWW-Authenticate")).isEmpty();
            // Later attempts hit the refetch rate limit (Nimbus allows a burst of two per interval); with no keys ever
            // loaded it is still an outage, not an invalid token.
            for (int i = 0; i < 3; i++) {
                assertThat(get(port(down), "/v1/probe/me", "Bearer " + token).statusCode()).as("retry " + i).isEqualTo(503);
            }
            assertLogsFreeOf(List.of(token));
        }
    }

    /** Another chain for other paths must not switch the API's protection off (C1/S4). */
    @Test
    void api_whenAnotherSecurityChainIsAdded_staysProtected() throws Exception {
        try (ConfigurableApplicationContext app = start(new Class<?>[]{App.class, ExtraChain.class})) {
            assertThat(get(port(app), "/v1/probe/open", null).statusCode()).isEqualTo(401);
            assertThat(get(port(app), "/v1/probe/open", "Bearer " + IDP.tokenFor("acct-x")).statusCode()).isEqualTo(200);
            assertThat(get(port(app), "/hooks/anything", null).statusCode()).as("the module's chain applies").isEqualTo(404);
        }
    }

    @Test
    void startup_whenCurrentAccountAnnotatesAnotherType_fails() {
        assertThatThrownBy(() -> start(new Class<?>[]{App.class, WrongAccountType.class}).close())
                .hasStackTraceContaining("@CurrentAccount must annotate an AccountId parameter, not String");
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
        assertThat(context.getBeanNamesForType(UserDetailsService.class)).isEmpty();
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

    /**
     * Message, arguments, MDC and the whole cause chain (T3). The rejection itself must have been logged, so an empty
     * log cannot pass for a clean one.
     */
    private void assertLogsFreeOf(List<String> tokens) {
        assertThat(appender.list).as("a rejection was logged").anyMatch(e -> e.getFormattedMessage().contains("code="));
        for (ILoggingEvent event : appender.list) {
            StringBuilder text = new StringBuilder(event.getFormattedMessage()).append(' ').append(event.getMDCPropertyMap());
            if (event.getArgumentArray() != null) {
                for (Object argument : event.getArgumentArray()) text.append(' ').append(argument);
            }
            for (var proxy = event.getThrowableProxy(); proxy != null; proxy = proxy.getCause()) {
                text.append(' ').append(proxy.getMessage());
            }
            String line = text.toString();
            for (String token : tokens) {
                // The signature part alone identifies a token; header and payload are base64 of public data.
                String signature = token.substring(token.lastIndexOf('.') + 1);
                if (signature.length() > 8) assertThat(line).as(event.getLoggerName()).doesNotContain(signature);
                assertThat(line).doesNotContain(token);
            }
        }
    }

    private static ConfigurableApplicationContext start(Class<?>[] sources, String... overrides) {
        List<String> properties = new ArrayList<>(List.of(IDP.propertyPairs()));
        properties.add("server.port=0");
        properties.addAll(List.of(overrides));
        return new SpringApplicationBuilder(sources).web(WebApplicationType.SERVLET)
                .properties(properties.toArray(String[]::new)).run();
    }

    private static int port(ConfigurableApplicationContext app) {
        return Integer.parseInt(app.getEnvironment().getProperty("local.server.port"));
    }

    private HttpResponse<String> get(String path, String authorization) throws Exception {
        return get(port, path, authorization);
    }

    private HttpResponse<String> get(int port, String path, String authorization) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
