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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

/** Rules of platform-security that need no server. */
class SecurityUnitTest {

    private static final URI JWKS = URI.create("http://idp.test.invalid/jwks");

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    /** A missing or unsafe setting stops the start instead of running with a weaker check (fail fast). */
    @Test
    void properties_whenIncompleteOrUnsafe_failAtStartup() {
        assertThatThrownBy(() -> new VersoJwtProperties(null, JWKS, "verso-api", Duration.ofSeconds(30), "", Duration.ofSeconds(2)))
                .hasMessageContaining("issuer");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", JWKS, " ", Duration.ofSeconds(30), "", Duration.ofSeconds(2)))
                .hasMessageContaining("audience");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", null, "verso-api", Duration.ofSeconds(30), "", Duration.ofSeconds(2)))
                .hasMessageContaining("jwk-set-uri");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", URI.create("file:///etc/jwks"), "verso-api",
                Duration.ofSeconds(30), "", Duration.ofSeconds(2))).hasMessageContaining("http(s)");
        assertThatThrownBy(() -> new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofMinutes(10), "", Duration.ofSeconds(2)))
                .hasMessageContaining("clock-skew");
    }

    /** RFC 9068: with a required typ header, a token without it (an ID token, for instance) is refused. */
    @Test
    void validator_whenTypeHeaderRequired_refusesOtherTypes() {
        VersoJwtProperties properties = new VersoJwtProperties("https://i", JWKS, "verso-api", Duration.ofSeconds(30),
                "at+jwt", Duration.ofSeconds(2));
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

    @SuppressWarnings("unused")
    private void controller(@CurrentAccount AccountId account) {}

    private static Jwt jwt(String typ) {
        Jwt.Builder builder = Jwt.withTokenValue("t").header("alg", "ES256")
                .issuer("https://i").audience(List.of("verso-api")).subject("acct-1")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (typ != null) builder.header("typ", typ);
        return builder.claims(c -> c.putAll(Map.of())).build();
    }
}
