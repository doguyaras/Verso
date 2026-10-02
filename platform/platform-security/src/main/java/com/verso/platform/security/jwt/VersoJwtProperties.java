package com.verso.platform.security.jwt;

import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Access token validation (reference 9.2, ADR-0005). The issuer is compared as an exact string and the keys come from
 * a separately configured JWKS URL: no discovery request at startup, and the application can reach the IdP on an
 * internal address while tokens carry the public issuer URL.
 *
 * @param issuer exact {@code iss} value, e.g. https://id.example.com/realms/verso
 * @param jwkSetUri where the signing keys (JWKS, {@code kid}) are fetched; rotation needs no restart
 * @param audience value that must be present in {@code aud}; an ID or refresh token of the same IdP carries another
 * @param clockSkew tolerated clock difference for {@code exp}/{@code nbf}
 * @param typeHeader required JOSE {@code typ} header (RFC 9068: {@code at+jwt}); empty when the IdP does not set it,
 *                   then {@code aud} alone separates access tokens from other tokens
 * @param jwksTimeout connect and read timeout of the JWKS request (above zero, at most 10 seconds)
 * @param allowHttp plain-HTTP JWKS URL on a non-loopback host; only for a private network such as the compose
 *                  network (ADR-0010). Anywhere else a man in the middle of the key fetch could mint any account's
 *                  token, so HTTP is refused unless the host is loopback or this is set explicitly.
 */
@ConfigurationProperties("verso.security.jwt")
public record VersoJwtProperties(
        String issuer,
        URI jwkSetUri,
        String audience,
        @DefaultValue("30s") Duration clockSkew,
        @DefaultValue("") String typeHeader,
        @DefaultValue("2s") Duration jwksTimeout,
        @DefaultValue("false") boolean allowHttp) {

    public VersoJwtProperties {
        require(issuer, "issuer");
        require(audience, "audience");
        if (jwkSetUri == null) throw new IllegalStateException("verso.security.jwt.jwk-set-uri is required");
        String scheme = jwkSetUri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException("verso.security.jwt.jwk-set-uri must be http(s)");
        }
        if ("http".equals(scheme) && !allowHttp && !isLoopback(jwkSetUri.getHost())) {
            throw new IllegalStateException("verso.security.jwt.jwk-set-uri must be https (or set allow-http on a private network)");
        }
        if (!jwksTimeout.isPositive() || jwksTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("verso.security.jwt.jwks-timeout must be above 0 and at most 10 seconds");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("verso.security.jwt.clock-skew must be between 0 and 2 minutes");
        }
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "[::1]".equals(host);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("verso.security.jwt." + name + " is required");
        }
    }
}
