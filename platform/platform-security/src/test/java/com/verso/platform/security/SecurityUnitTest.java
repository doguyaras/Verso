package com.verso.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.security.jwt.JwtValidation;
import com.verso.platform.security.jwt.VersoJwtProperties;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.platform.security.web.CurrentAccountArgumentResolver;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import com.sun.net.httpserver.HttpServer;
import com.verso.platform.security.testing.TestIdentityProvider;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rules of platform-security that need no server. */
class SecurityUnitTest {

    private static final URI JWKS = URI.create("https://idp.test.invalid/jwks");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A missing or unsafe setting stops the start instead of running with a weaker check (fail fast). */
    @Test
    void properties_whenIncompleteOrUnsafe_failAtStartup() {
        assertThatThrownBy(() -> new VersoJwtProperties(null, JWKS, "verso-api", Duration.ofSeconds(30), "", Duration.ofSeconds(2), false))
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", JWKS, " ", Duration.ofSeconds(30), "", Duration.ofSeconds(2), false))
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", null, "verso-api", Duration.ofSeconds(30), "", Duration.ofSeconds(2), false))
                .hasMessageContaining("jwk-set-uri");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", URI.create("file:///etc/jwks"), "verso-api",
                Duration.ofSeconds(30), "", Duration.ofSeconds(2), false)).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofMinutes(10), "", Duration.ofSeconds(2), false))
                .hasMessageContaining("clock-skew");
        assertThatThrownBy(() -> properties(URI.create("http://idp.example.com/jwks"), Duration.ofSeconds(2), false))
                .as("plain HTTP to a remote host").hasMessageContaining("must be https");
        assertThatThrownBy(() -> properties(JWKS, Duration.ZERO, false)).hasMessageContaining("jwks-timeout");
        assertThatThrownBy(() -> properties(JWKS, Duration.ofMinutes(1), false)).hasMessageContaining("jwks-timeout");
    }

    /** HTTP only to loopback, or to a private network host when allow-http says so explicitly (compose). */
    @Test
    void properties_whenHttpIsLoopbackOrExplicitlyAllowed_areAccepted() {
        for (String uri : List.of("http://localhost:8180/jwks", "http://127.0.0.1:1/jwks", "http://[::1]:1/jwks")) {
            assertThat(properties(URI.create(uri), Duration.ofSeconds(2), false).jwkSetUri()).hasToString(uri);
        }
        assertThat(properties(URI.create("http://keycloak:8080/jwks"), Duration.ofSeconds(2), true).allowHttp()).isTrue();
    }

    /** RFC 9068 4: the type may also be written as the full media type. */
    @Test
    void validator_whenTypeHeaderIsTheFullMediaType_acceptsIt() {
        OAuth2TokenValidator<Jwt> validator = JwtValidation.validator(new VersoJwtProperties("https://i", JWKS, "verso-api",
                Duration.ofSeconds(30), "at+jwt", Duration.ofSeconds(2), false), Clock.systemUTC());
        assertThat(validator.validate(jwt("application/at+jwt")).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("application/jwt")).hasErrors()).isTrue();
    }

    private static VersoJwtProperties properties(URI jwks, Duration timeout, boolean allowHttp) {
        return new VersoJwtProperties("https://i", jwks, "verso-api", Duration.ofSeconds(30), "", timeout, allowHttp);
    }

    /** RFC 9068: with a required typ header, a token without it (an ID token, for instance) is refused. */
    @Test
    void validator_whenTypeHeaderRequired_refusesOtherTypes() {
        VersoJwtProperties properties = new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofSeconds(30),
                "at+jwt", Duration.ofSeconds(2), false);
        OAuth2TokenValidator<Jwt> validator = JwtValidation.validator(properties, Clock.systemUTC());
        assertThat(validator.validate(jwt("at+jwt")).hasErrors()).isFalse();
        assertThat(validator.validate(jwt("AT+JWT")).hasErrors()).as("media type names are case-insensitive").isFalse();
        assertThat(validator.validate(jwt("JWT")).hasErrors()).isTrue();
        assertThat(validator.validate(jwt(null)).hasErrors()).isTrue();
    }

    @Test
    void currentAccount_whenNoAuthenticatedJwt_failsClosed() throws Exception {
        Method method = SecurityUnitTest.class.getDeclaredMethod("controller", AccountId.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        CurrentAccountArgumentResolver resolver = new CurrentAccountArgumentResolver();
        assertThat(resolver.supportsParameter(parameter)).isTrue();
        assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
                .isInstanceOfSatisfying(ServiceException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
    }

    /**
     * platform-core recognises Spring Security's exceptions by class name (no security dependency there). A renamed
     * class would silently turn a 403 into a 500 again: the names must exist on this classpath.
     */
    @Test
    void securityExceptionNames_whenUsedByPlatformCore_resolveToRealClasses() throws Exception {
        Field field = Class.forName("com.verso.platform.core.handler.ErrorClassifier").getDeclaredField("SECURITY_EXCEPTIONS");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<String> names = (List<String>) field.get(null);
        assertThat(names).hasSize(2);
        for (String name : names) {
            assertThat(RuntimeException.class.isAssignableFrom(Class.forName(name))).as(name).isTrue();
        }
    }

    /** The skew is 30 s and measured with the injected clock, not the system clock (T5). */
    @Test
    void validator_whenTimeIsChecked_usesTheConfiguredSkewAndTheInjectedClock() {
        Instant now = Instant.parse("2030-01-01T12:00:00Z");
        OAuth2TokenValidator<Jwt> validator = JwtValidation.validator(properties(JWKS, Duration.ofSeconds(2), false),
                Clock.fixed(now, ZoneOffset.UTC));
        assertThat(validator.validate(jwtExpiringAt(now.minusSeconds(20))).hasErrors()).as("within 30 s").isFalse();
        assertThat(validator.validate(jwtExpiringAt(now.minusSeconds(45))).hasErrors()).as("beyond 30 s").isTrue();
        assertThat(validator.validate(jwtExpiringAt(Instant.now().plusSeconds(300))).hasErrors())
                .as("valid by system time only").isTrue();
        assertThat(new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofMinutes(2), "", Duration.ofSeconds(2), false)
                .clockSkew()).isEqualTo(Duration.ofMinutes(2));
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofSeconds(121), "",
                Duration.ofSeconds(2), false)).hasMessageContaining("clock-skew");
    }

    /** A JWKS endpoint that never answers costs at most the configured timeout (T2; AGENTS.md 5). */
    @Test
    void decoder_whenJwksDoesNotAnswer_failsWithinTheConfiguredTimeout() throws Exception {
        HttpServer slow = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        slow.createContext("/jwks", exchange -> {
            try {
                Thread.sleep(8000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        slow.start();
        try {
            URI jwks = URI.create("http://127.0.0.1:" + slow.getAddress().getPort() + "/jwks");
            JwtDecoder decoder = JwtValidation.decoder(new VersoJwtProperties(TestIdentityProvider.ISSUER, jwks,
                    "verso-api", Duration.ofSeconds(30), "", Duration.ofMillis(300), false), Clock.systemUTC());
            long started = System.nanoTime();
            assertThatThrownBy(() -> decoder.decode(TestIdentityProvider.get().tokenFor("acct-slow")));
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(4));
        } finally {
            slow.stop(0);
        }
    }

    /** Only a validated JWT carries an account; any other authentication fails closed (T11). */
    @Test
    void currentAccount_whenAuthenticationIsNotAJwt_failsClosed() throws Exception {
        MethodParameter parameter = new MethodParameter(SecurityUnitTest.class.getDeclaredMethod("controller", AccountId.class), 0);
        CurrentAccountArgumentResolver resolver = new CurrentAccountArgumentResolver();
        var others = List.of(
                new AnonymousAuthenticationToken("key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")),
                UsernamePasswordAuthenticationToken.authenticated("someone", null, List.of()));
        for (var authentication : others) {
            SecurityContextHolder.getContext().setAuthentication(authentication);
            assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
                    .as(authentication.getClass().getSimpleName())
                    .isInstanceOfSatisfying(ServiceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.UNAUTHENTICATED));
        }
    }

    /** The resolver claims every @CurrentAccount parameter and refuses a non-AccountId one (S1). */
    @Test
    void currentAccount_whenParameterIsNotAnAccountId_isClaimedAndRefused() throws Exception {
        MethodParameter parameter = new MethodParameter(SecurityUnitTest.class.getDeclaredMethod("wrongController", String.class), 0);
        CurrentAccountArgumentResolver resolver = new CurrentAccountArgumentResolver();
        assertThat(resolver.supportsParameter(parameter)).as("never left to request binding").isTrue();
        assertThatThrownBy(() -> resolver.resolveArgument(parameter, null, null, null))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("@CurrentAccount");
    }

    @SuppressWarnings("unused")
    private void controller(@CurrentAccount AccountId account) {}

    @SuppressWarnings("unused")
    private void wrongController(@CurrentAccount String account) {}

    private static Jwt jwtExpiringAt(Instant exp) {
        return Jwt.withTokenValue("t").header("alg", "ES256").issuer("https://i").audience(List.of("verso-api"))
                .subject("acct-1").issuedAt(exp.minusSeconds(300)).expiresAt(exp).build();
    }

    private static Jwt jwt(String typ) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "ES256")
                .issuer("https://i").audience(List.of("verso-api")).subject("acct-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (typ != null) builder.header("typ", typ);
        return builder.claims(c -> c.putAll(Map.of())).build();
    }
}
