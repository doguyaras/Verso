package com.verso.platform.security.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The access token rules of reference 9.2 and ADR-0005, in one place.
 *
 * <ul>
 *   <li>Asymmetric signature, algorithm pinned to ES256. The verifier is never chosen from the token's {@code alg}:
 *       HS256 signed with the public key bytes (alg confusion), RS256 and {@code none} are rejected.</li>
 *   <li>{@code iss} exact, {@code aud} contains the API audience, {@code exp} required, {@code nbf}/{@code exp} with
 *       a bounded clock skew.</li>
 *   <li>{@code sub} required, bounded and printable: it becomes the account id (reference 6.5).</li>
 *   <li>JOSE {@code typ} header when configured (RFC 9068 {@code at+jwt}, also as {@code application/at+jwt}).</li>
 * </ul>
 *
 * <p>The keys come from the JWKS URL through a cache (ADR-0010, phase 3 resilience review): fetched with a timeout and
 * a size limit, kept for {@link #KEY_CACHE_TTL}, refetched for an unknown {@code kid} at most once per
 * {@link #REFETCH_MIN_INTERVAL} (an unauthenticated caller with made-up key ids cannot make Verso hammer the IdP), and
 * still used for {@link #OUTAGE_TOLERANCE} while the IdP is unreachable. Signatures are always checked: an outage
 * only means the last known keys stay valid, never that a token is accepted unchecked.
 *
 * <p>EdDSA is not enabled: Nimbus' Ed25519 verifier needs the optional Tink library at run time (reference 9.2 note);
 * ES256 alone is stricter than ADR-0005's "EdDSA or ES256" (ADR-0007 #49).
 */
public final class JwtValidation {

    public static final SignatureAlgorithm ALGORITHM = SignatureAlgorithm.ES256;
    /** An account id is printable, without separators that would let it be confused with a path or a list. */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._@:-]{1,255}");
    public static final Duration KEY_CACHE_TTL = Duration.ofMinutes(5);
    public static final Duration REFETCH_MIN_INTERVAL = Duration.ofSeconds(30);
    public static final Duration OUTAGE_TOLERANCE = Duration.ofHours(1);
    /** A JWKS document is a few keys; anything larger is not one. */
    private static final int JWKS_SIZE_LIMIT = 64 * 1024;

    private JwtValidation() {}

    public static JwtDecoder decoder(VersoJwtProperties properties, Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keySource(properties))
                .jwsAlgorithm(ALGORITHM)
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
    }

    static JWKSource<SecurityContext> keySource(VersoJwtProperties properties) {
        int timeout = Math.toIntExact(properties.jwksTimeout().toMillis());
        JWKSource<SecurityContext> cached;
        try {
            cached = JWKSourceBuilder.<SecurityContext>create(properties.jwkSetUri().toURL(),
                            new DefaultResourceRetriever(timeout, timeout, JWKS_SIZE_LIMIT))
                    // Other requests wait for a running refresh at most one fetch long.
                    .cache(KEY_CACHE_TTL.toMillis(), timeout + 1000L)
                    .rateLimited(REFETCH_MIN_INTERVAL.toMillis())
                    .outageTolerant(OUTAGE_TOLERANCE.toMillis())
                    .retrying(false)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("verso.security.jwt.jwk-set-uri is not a URL", e);
        }
        // A refetch refused by the rate limit, once keys were loaded, means "the cached keys are all there is": the
        // token's kid is unknown, so the token is invalid (401). Left as an exception it became "IdP unavailable" (503)
        // for every made-up kid. Before the first successful load it stays an outage (503): there is nothing to trust.
        AtomicBoolean loaded = new AtomicBoolean();
        return (selector, context) -> {
            try {
                List<JWK> keys = cached.get(selector, context);
                loaded.set(true);
                return keys;
            } catch (RateLimitReachedException e) {
                if (!loaded.get()) throw e;
                return List.of();
            }
        };
    }

    public static OAuth2TokenValidator<Jwt> validator(VersoJwtProperties properties, Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(properties.clockSkew());
        timestamps.setClock(clock);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(require(jwt -> jwt.getExpiresAt() != null, "exp is required"));
        validators.add(timestamps);
        validators.add(new JwtIssuerValidator(properties.issuer()));
        validators.add(require(jwt -> jwt.getAudience() != null && jwt.getAudience().contains(properties.audience()),
                "aud does not contain the API audience"));
        validators.add(require(jwt -> jwt.getSubject() != null && SUBJECT.matcher(jwt.getSubject()).matches(),
                "sub is missing or not a valid account id"));
        if (!properties.typeHeader().isBlank()) {
            validators.add(require(jwt -> mediaType(properties.typeHeader()).equals(mediaType(jwt.getHeaders().get("typ"))),
                    "typ header is not " + properties.typeHeader()));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    /** RFC 7515 4.1.9 / RFC 9068 4: "at+jwt" and "application/at+jwt" are the same type; names are case-insensitive. */
    private static String mediaType(Object typ) {
        String value = String.valueOf(typ).toLowerCase(Locale.ROOT);
        return value.startsWith("application/") ? value.substring("application/".length()) : value;
    }

    private static OAuth2TokenValidator<Jwt> require(Predicate<Jwt> rule, String description) {
        OAuth2Error error = new OAuth2Error("invalid_token", description, null);
        return jwt -> rule.test(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
    }
}
