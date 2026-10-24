package com.verso.platform.security.jwt;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.util.DefaultResourceRetriever;
import java.net.MalformedURLException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.MappedJwtClaimSetConverter;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * The access token rules of reference 9.2, ADR-0005, ADR-0010 and ADR-0041, in one place.
 *
 * <ul>
 *   <li>Trusted issuers only (ADR-0041): the token's {@code iss} picks the issuer's own decoder from an allowlist
 *       before any key is fetched; an unknown issuer is refused without a request.</li>
 *   <li>Asymmetric signature, algorithms pinned per issuer (Keycloak ES256, Entra ID RS256). The verifier is never
 *       chosen from the token's {@code alg}: HS256 signed with the public key bytes (alg confusion), an algorithm the
 *       issuer does not use and {@code none} are rejected; the key type must match the algorithm.</li>
 *   <li>{@code iss} exact, {@code aud} contains one of the issuer's audiences for the surface, {@code exp} required,
 *       {@code nbf}/{@code exp} with a bounded clock skew; the issuer's fixed claims (Entra: {@code ver},
 *       {@code tid}).</li>
 *   <li>{@code sub} required, bounded and printable: it becomes the account id (reference 6.5). For Entra ID it is the
 *       {@code oid} claim.</li>
 *   <li>JOSE {@code typ} header per issuer (RFC 9068 {@code at+jwt}, also as {@code application/at+jwt}; Entra
 *       {@code JWT}, with a required {@code scp} so that an ID token never passes).</li>
 * </ul>
 *
 * <p>The keys come from each issuer's JWKS URL through a cache (ADR-0010, phase 3 resilience review): fetched with a
 * timeout and a size limit, kept for {@link #KEY_CACHE_TTL}, refetched for an unknown {@code kid} at most once per
 * {@link #REFETCH_MIN_INTERVAL} (an unauthenticated caller with made-up key ids cannot make Verso hammer the IdP), and
 * still used for {@link #OUTAGE_TOLERANCE} while the IdP is unreachable. Signatures are always checked: an outage
 * only means the last known keys stay valid, never that a token is accepted unchecked.
 *
 * <p>EdDSA is not enabled: Nimbus' Ed25519 verifier needs the optional Tink library at run time (reference 9.2 note).
 */
public final class JwtValidation {

    /** An account id is printable, without separators that would let it be confused with a path or a list. */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._@:-]{1,255}");
    public static final Duration KEY_CACHE_TTL = Duration.ofMinutes(5);
    public static final Duration REFETCH_MIN_INTERVAL = Duration.ofSeconds(30);
    public static final Duration OUTAGE_TOLERANCE = Duration.ofHours(1);
    /** A JWKS document is a few keys; anything larger is not one. */
    private static final int JWKS_SIZE_LIMIT = 64 * 1024;

    private JwtValidation() {}

    /** The primary issuer alone, with its API audience (single-issuer callers and tests). */
    public static JwtDecoder decoder(VersoJwtProperties properties, Clock clock) {
        return decoder(List.of(primary(properties)), TrustedIssuer::audiences, properties, clock);
    }

    /**
     * The decoder for one surface (the API or MCP, ADR-0031) over all trusted issuers (ADR-0041): the token's
     * {@code iss} selects the issuer's own decoder first, from the allowlist only. An issuer without an audience for
     * this surface is not trusted on it.
     *
     * @param settings the shared JWKS timeout and clock skew
     */
    public static JwtDecoder decoder(List<TrustedIssuer> issuers, Function<TrustedIssuer, List<String>> audiences,
                                     VersoJwtProperties settings, Clock clock) {
        Map<String, JwtDecoder> decoders = new LinkedHashMap<>();
        for (TrustedIssuer issuer : issuers) {
            List<String> accepted = audiences.apply(issuer);
            if (!accepted.isEmpty()) decoders.put(issuer.issuer(), decoder(issuer, accepted, settings, clock));
        }
        if (decoders.isEmpty()) throw new IllegalStateException("no trusted issuer has an audience for this surface");
        return new IssuerSelectingJwtDecoder(decoders);
    }

    private static JwtDecoder decoder(TrustedIssuer issuer, List<String> audiences, VersoJwtProperties settings,
                                      Clock clock) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSource(keySource(issuer.jwkSetUri(), settings.jwksTimeout()))
                // Only the issuer's own algorithms. Nimbus then selects only keys of the matching type (EC with the
                // algorithm's curve for ES*, RSA for RS*/PS*), so an RSA key never verifies an ES256 token or the reverse.
                .jwsAlgorithms(algorithms -> {
                    algorithms.clear();
                    algorithms.addAll(issuer.algorithms());
                })
                .build();
        decoder.setClaimSetConverter(claimSet(issuer.accountClaim()));
        decoder.setJwtValidator(validator(issuer, audiences, settings.clockSkew(), clock));
        return decoder;
    }

    private static TrustedIssuer primary(VersoJwtProperties properties) {
        return new TrustedIssuer(TrustedIssuers.PRIMARY, properties.issuer(), properties.jwkSetUri(),
                Set.copyOf(properties.algorithms()), properties.typeHeader(), List.of(properties.audience()), List.of(),
                "sub", Map.of(), false, false, null);
    }

    /**
     * Spring's standard claim conversion, then the account: when the issuer names another claim than {@code sub}
     * (Entra: {@code oid}, stable across the tenant's applications, where {@code sub} is pairwise per application), that
     * claim replaces {@code sub} before validation, so the account rule below and every reader of {@code sub} see it.
     * A token without that claim has no {@code sub} and is refused.
     */
    static Converter<Map<String, Object>, Map<String, Object>> claimSet(String accountClaim) {
        MappedJwtClaimSetConverter defaults = MappedJwtClaimSetConverter.withDefaults(Map.of());
        if (JwtClaimNames.SUB.equals(accountClaim)) return defaults;
        return claims -> {
            Map<String, Object> converted = new HashMap<>(defaults.convert(claims));
            converted.remove(JwtClaimNames.SUB);
            if (claims.get(accountClaim) instanceof String account) converted.put(JwtClaimNames.SUB, account);
            return converted;
        };
    }

    static JWKSource<SecurityContext> keySource(URI jwkSetUri, Duration jwksTimeout) {
        int timeout = Math.toIntExact(jwksTimeout.toMillis());
        JWKSource<SecurityContext> cached;
        try {
            cached = JWKSourceBuilder.<SecurityContext>create(jwkSetUri.toURL(),
                            new DefaultResourceRetriever(timeout, timeout, JWKS_SIZE_LIMIT))
                    // Other requests wait for a running refresh at most one fetch long.
                    .cache(KEY_CACHE_TTL.toMillis(), timeout + 1000L)
                    .rateLimited(REFETCH_MIN_INTERVAL.toMillis())
                    .outageTolerant(OUTAGE_TOLERANCE.toMillis())
                    .retrying(false)
                    .build();
        } catch (MalformedURLException e) {
            throw new IllegalStateException("a trusted issuer's jwk-set-uri is not a URL", e);
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
        return validator(primary(properties), List.of(properties.audience()), properties.clockSkew(), clock);
    }

    static OAuth2TokenValidator<Jwt> validator(TrustedIssuer issuer, List<String> audiences, Duration clockSkew,
                                               Clock clock) {
        JwtTimestampValidator timestamps = new JwtTimestampValidator(clockSkew);
        timestamps.setClock(clock);
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(require(jwt -> jwt.getExpiresAt() != null, "exp is required"));
        validators.add(timestamps);
        validators.add(new JwtIssuerValidator(issuer.issuer()));
        validators.add(require(jwt -> jwt.getAudience() != null && jwt.getAudience().stream().anyMatch(audiences::contains),
                "aud does not contain an accepted audience"));
        validators.add(require(jwt -> jwt.getSubject() != null && SUBJECT.matcher(jwt.getSubject()).matches(),
                "sub is missing or not a valid account id"));
        if (!issuer.typeHeader().isBlank()) {
            validators.add(require(jwt -> mediaType(issuer.typeHeader()).equals(mediaType(jwt.getHeaders().get("typ"))),
                    "typ header is not " + issuer.typeHeader()));
        }
        issuer.requiredClaims().forEach((claim, value) -> validators.add(
                require(jwt -> value.equals(jwt.getClaims().get(claim)), claim + " is not the expected value")));
        if (issuer.requireScope()) {
            // A user's access token carries the delegated scopes; an ID token (same issuer, same keys and, at Entra,
            // the same typ) never does.
            validators.add(require(jwt -> jwt.getClaims().get("scp") instanceof String scp && !scp.isBlank(),
                    "scp is required"));
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
