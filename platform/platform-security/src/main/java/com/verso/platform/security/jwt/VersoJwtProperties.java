package com.verso.platform.security.jwt;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * Access token validation (reference 9.2, ADR-0005, ADR-0041). The keys below the prefix describe the primary trusted
 * issuer (the bundled Keycloak or any OIDC IdP that issues RFC 9068 tokens); {@link Entra} optionally adds Microsoft
 * Entra ID as a second, separately validated issuer. Each issuer is compared as an exact string and its keys come from
 * a separately configured JWKS URL: no discovery request at startup, and the application can reach the IdP on an
 * internal address while tokens carry the public issuer URL.
 *
 * @param issuer exact {@code iss} value, e.g. https://id.example.com/realms/verso; empty (together with
 *               {@code jwkSetUri}) only when Entra ID is the sole issuer
 * @param jwkSetUri where the signing keys (JWKS, {@code kid}) are fetched; rotation needs no restart
 * @param audience value that must be present in {@code aud}; an ID or refresh token of the same IdP carries another
 * @param clockSkew tolerated clock difference for {@code exp}/{@code nbf}
 * @param typeHeader required JOSE {@code typ} header (RFC 9068: {@code at+jwt}); empty when the IdP does not set it,
 *                   then {@code aud} alone separates access tokens from other tokens
 * @param jwksTimeout connect and read timeout of the JWKS request (above zero, at most 10 seconds)
 * @param allowHttp plain-HTTP JWKS URL on a non-loopback host; only for a private network such as the compose
 *                  network (ADR-0010). Anywhere else a man in the middle of the key fetch could mint any account's
 *                  token, so HTTP is refused unless the host is loopback or this is set explicitly.
 * @param algorithms the signature algorithms of the primary issuer (ES256). Never chosen from the token's
 *                   {@code alg}: a token whose algorithm is not listed is refused. The type has no HMAC or
 *                   {@code none} member, so a symmetric algorithm cannot even be configured.
 * @param entra Microsoft Entra ID as an additional issuer; disabled while its tenant id is empty
 */
@ConfigurationProperties("verso.security.jwt")
public record VersoJwtProperties(
        String issuer,
        URI jwkSetUri,
        String audience,
        Duration clockSkew,
        String typeHeader,
        Duration jwksTimeout,
        boolean allowHttp,
        List<SignatureAlgorithm> algorithms,
        Entra entra) {

    @ConstructorBinding
    public VersoJwtProperties(
            String issuer,
            URI jwkSetUri,
            String audience,
            @DefaultValue("30s") Duration clockSkew,
            @DefaultValue("") String typeHeader,
            @DefaultValue("2s") Duration jwksTimeout,
            @DefaultValue("false") boolean allowHttp,
            @DefaultValue("ES256") List<SignatureAlgorithm> algorithms,
            @DefaultValue Entra entra) {
        issuer = issuer == null ? "" : issuer.trim();
        // An empty environment value binds to an empty URI rather than null.
        jwkSetUri = jwkSetUri == null || jwkSetUri.toString().isBlank() ? null : jwkSetUri;
        typeHeader = typeHeader == null ? "" : typeHeader;
        algorithms = algorithms == null ? List.of(SignatureAlgorithm.ES256) : List.copyOf(algorithms);
        entra = entra == null ? Entra.DISABLED : entra;
        if (primaryEnabled(issuer, jwkSetUri) || !entra.enabled()) {
            require(issuer, "issuer");
            require(audience, "audience");
            if (jwkSetUri == null) throw new IllegalStateException("verso.security.jwt.jwk-set-uri is required");
            requireKeyUri(jwkSetUri, allowHttp, "verso.security.jwt.jwk-set-uri");
            if (algorithms.isEmpty()) throw new IllegalStateException("verso.security.jwt.algorithms must not be empty");
        }
        if (!jwksTimeout.isPositive() || jwksTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("verso.security.jwt.jwks-timeout must be above 0 and at most 10 seconds");
        }
        if (clockSkew.isNegative() || clockSkew.compareTo(Duration.ofMinutes(2)) > 0) {
            throw new IllegalStateException("verso.security.jwt.clock-skew must be between 0 and 2 minutes");
        }
        if (entra.enabled()) {
            requireKeyUri(entra.keySetUri(), allowHttp, "verso.security.jwt.entra.jwk-set-uri");
            if (entra.issuer().equals(issuer)) {
                throw new IllegalStateException("verso.security.jwt.entra must not repeat the primary issuer");
            }
        }
        this.issuer = issuer;
        this.jwkSetUri = jwkSetUri;
        this.audience = audience;
        this.clockSkew = clockSkew;
        this.typeHeader = typeHeader;
        this.jwksTimeout = jwksTimeout;
        this.allowHttp = allowHttp;
        this.algorithms = algorithms;
        this.entra = entra;
    }

    /** The primary issuer alone, ES256, no Entra: the shape every caller had before ADR-0041. */
    public VersoJwtProperties(String issuer, URI jwkSetUri, String audience, Duration clockSkew, String typeHeader,
                              Duration jwksTimeout, boolean allowHttp) {
        this(issuer, jwkSetUri, audience, clockSkew, typeHeader, jwksTimeout, allowHttp,
                List.of(SignatureAlgorithm.ES256), Entra.DISABLED);
    }

    /** False only for an Entra-only installation, where the primary issuer and its key URL are both left empty. */
    public boolean primaryEnabled() {
        return primaryEnabled(issuer, jwkSetUri);
    }

    private static boolean primaryEnabled(String issuer, URI jwkSetUri) {
        return !issuer.isBlank() || jwkSetUri != null;
    }

    static void requireKeyUri(URI uri, boolean allowHttp, String name) {
        String scheme = uri.getScheme();
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw new IllegalStateException(name + " must be http(s)");
        }
        if ("http".equals(scheme) && !allowHttp && !isLoopback(uri.getHost())) {
            throw new IllegalStateException(name + " must be https (or set allow-http on a private network)");
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

    /**
     * Microsoft Entra ID access tokens, accepted directly (ADR-0041): single tenant, v2.0 tokens only, RS256 only,
     * JOSE {@code typ: JWT}. The issuer is derived ({@code <authority>/<tenant-id>/v2.0}), so it always names the
     * tenant; multi-tenant authorities ({@code common}, {@code organizations}, {@code consumers}) cannot be configured.
     *
     * @param tenantId     the directory (tenant) id, a GUID; empty disables Entra
     * @param authority    the Entra sign-in host for the cloud (public: https://login.microsoftonline.com)
     * @param jwkSetUri    where the keys are fetched; empty means {@code <authority>/<tenant-id>/discovery/v2.0/keys}.
     *                     Set it to a mirror when the application has no route out (local mode, ADR-0013).
     * @param audiences    the API's application (client) id and/or Application ID URI; {@code aud} must be one of
     *                     them
     * @param mcpAudiences the MCP resource's own application id(s) (ADR-0031); empty: Entra tokens are not accepted
     *                     on /mcp. Never one of {@code audiences}.
     * @param rolesClaim   app roles (ADR-0019)
     * @param groupsClaim  group object ids; empty when group claims are not configured
     */
    public record Entra(
            @DefaultValue("") String tenantId,
            @DefaultValue("https://login.microsoftonline.com") String authority,
            @DefaultValue("") String jwkSetUri,
            @DefaultValue List<String> audiences,
            @DefaultValue List<String> mcpAudiences,
            @DefaultValue("roles") String rolesClaim,
            @DefaultValue("groups") String groupsClaim) {

        public static final Entra DISABLED =new Entra("", "https://login.microsoftonline.com", "", List.of(), List.of(),
                "roles", "groups");
        private static final Pattern GUID =
                Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
        /** The tenant of personal Microsoft accounts: never a company directory. */
        private static final String CONSUMERS_TENANT = "9188040d-6c67-4c5b-b112-36a304b66dad";
        private static final String PREFIX = "verso.security.jwt.entra.";

        public Entra {
            tenantId = tenantId == null ? "" : tenantId.trim().toLowerCase(Locale.ROOT);
            authority = authority == null ? "" : authority.trim().replaceAll("/+$", "");
            jwkSetUri = jwkSetUri == null ? "" : jwkSetUri.trim();
            audiences = clean(audiences);
            mcpAudiences = clean(mcpAudiences);
            rolesClaim = rolesClaim == null ? "" : rolesClaim.trim();
            groupsClaim = groupsClaim == null ? "" : groupsClaim.trim();
            if (!tenantId.isEmpty()) {
                if (!GUID.matcher(tenantId).matches() || CONSUMERS_TENANT.equals(tenantId)) {
                    throw new IllegalStateException(PREFIX + "tenant-id must be the directory's GUID "
                            + "(multi-tenant authorities such as common or organizations are not accepted)");
                }
                URI host = URI.create(authority);
                if (!"https".equals(host.getScheme()) || host.getHost() == null
                        || !(host.getRawPath() == null || host.getRawPath().isEmpty())
                        || host.getRawQuery() != null || host.getRawFragment() != null || host.getRawUserInfo() != null) {
                    throw new IllegalStateException(PREFIX + "authority must be an https origin without a path");
                }
                if (audiences.isEmpty()) throw new IllegalStateException(PREFIX + "audiences is required");
                Set<String> overlap = new HashSet<>(audiences);
                overlap.retainAll(mcpAudiences);
                if (!overlap.isEmpty()) {
                    throw new IllegalStateException(PREFIX + "mcp-audiences must differ from audiences (ADR-0031)");
                }
            }
        }

        public boolean enabled() {
            return !tenantId.isEmpty();
        }

        /** The v2.0 issuer: what {@code iss} of every accepted token must be, exactly. */
        public String issuer() {
            return authority + "/" + tenantId + "/v2.0";
        }

        public URI keySetUri() {
            return URI.create(jwkSetUri.isEmpty() ? authority + "/" + tenantId + "/discovery/v2.0/keys" : jwkSetUri);
        }

        private static List<String> clean(List<String> values) {
            return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
        }
    }
}
