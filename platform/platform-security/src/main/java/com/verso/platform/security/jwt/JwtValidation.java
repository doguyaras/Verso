package com.verso.platform.security.jwt;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestTemplate;

/**
 * The access token rules of reference 9.2 and ADR-0005, in one place.
 *
 * <ul>
 *   <li>Asymmetric signature, algorithm pinned to ES256. The verifier is never chosen from the token's {@code alg}:
 *       HS256 signed with the public key bytes (alg confusion), RS256 and {@code none} are rejected.</li>
 *   <li>{@code iss} exact, {@code aud} contains the API audience, {@code exp} required, {@code nbf}/{@code exp} with
 *       a bounded clock skew.</li>
 *   <li>{@code sub} required, bounded and printable: it becomes the account id (reference 6.5).</li>
 *   <li>JOSE {@code typ} header when configured (RFC 9068 {@code at+jwt}).</li>
 * </ul>
 *
 * <p>EdDSA is not enabled: Nimbus' Ed25519 verifier needs the optional Tink library at run time (reference 9.2 note);
 * ES256 alone is stricter than ADR-0005's "EdDSA or ES256" (ADR-0007 #49).
 */
public final class JwtValidation {

    public static final SignatureAlgorithm ALGORITHM = SignatureAlgorithm.ES256;
    /** An account id is printable, without separators that would let it be confused with a path or a list. */
    private static final Pattern SUBJECT = Pattern.compile("[A-Za-z0-9._@:-]{1,255}");

    private JwtValidation() {}

    public static JwtDecoder decoder(VersoJwtProperties properties, Clock clock) {
        SimpleClientHttpRequestFactory http = new SimpleClientHttpRequestFactory();
        http.setConnectTimeout(properties.jwksTimeout());
        http.setReadTimeout(properties.jwksTimeout());
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(properties.jwkSetUri().toString())
                .jwsAlgorithm(ALGORITHM)
                .restOperations(new RestTemplate(http))
                .build();
        decoder.setJwtValidator(validator(properties, clock));
        return decoder;
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
            validators.add(require(jwt -> properties.typeHeader().equalsIgnoreCase(String.valueOf(jwt.getHeaders().get("typ"))),
                    "typ header is not " + properties.typeHeader()));
        }
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static OAuth2TokenValidator<Jwt> require(java.util.function.Predicate<Jwt> rule, String description) {
        OAuth2Error error = new OAuth2Error("invalid_token", description, null);
        return jwt -> rule.test(jwt) ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(error);
    }

    /** The claim names used here, for tests and documentation. */
    public static List<String> requiredClaims() {
        return List.of(JwtClaimNames.ISS, JwtClaimNames.AUD, JwtClaimNames.SUB, JwtClaimNames.EXP);
    }
}
