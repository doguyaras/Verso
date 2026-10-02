package com.verso.platform.security.testing;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A test identity provider: an ES256 signing key published as JWKS by the JDK's own HTTP server, and a token builder
 * that can also produce every token the API must refuse (other algorithms, unknown key, wrong claims). The production
 * decoder is tested against it unchanged: same JWKS request, same pinned algorithm, same validators.
 *
 * <p>The JWKS also lists an RSA key: an RS256 token signed with a published key is still refused, because the
 * verifier is pinned to ES256 and never chosen from the token's {@code alg} (reference 9.2). One provider per JVM,
 * shared by cached Spring contexts; it is never stopped.
 */
public final class TestIdentityProvider {

    public static final String ISSUER = "https://idp.test.invalid/realms/verso";
    public static final String AUDIENCE = "verso-api";
    /** RFC 9068, as the bundled Keycloak issues it (client attribute access.token.header.type.rfc9068). */
    public static final String ACCESS_TOKEN_TYPE = "at+jwt";

    private static final TestIdentityProvider INSTANCE = new TestIdentityProvider();

    private final ECKey signingKey;
    private final ECKey unknownKey;
    private final RSAKey rsaKey;
    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    private TestIdentityProvider() {
        try {
            signingKey = new ECKeyGenerator(Curve.P_256).keyID("test-es256").generate();
            unknownKey = new ECKeyGenerator(Curve.P_256).keyID("not-published").generate();
            rsaKey = new RSAKeyGenerator(2048).keyID("test-rs256").generate();
            byte[] jwks = new JWKSet(List.of(signingKey.toPublicJWK(), rsaKey.toPublicJWK())).toString()
                    .getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/jwks", exchange -> {
                jwksRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("test identity provider", e);
        }
    }

    public static TestIdentityProvider get() {
        return INSTANCE;
    }

    public String jwkSetUri() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/jwks";
    }

    public int jwksRequests() {
        return jwksRequests.get();
    }

    /** The Spring properties that point the production decoder at this provider. */
    public Map<String, String> properties() {
        return Map.of(
                "verso.security.jwt.issuer", ISSUER,
                "verso.security.jwt.jwk-set-uri", jwkSetUri(),
                "verso.security.jwt.audience", AUDIENCE,
                "verso.security.jwt.type-header", ACCESS_TOKEN_TYPE);
    }

    public String[] propertyPairs() {
        return properties().entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toArray(String[]::new);
    }

    /** A valid access token for the given account. */
    public String tokenFor(String subject) {
        return token().subject(subject).build();
    }

    public Builder token() {
        return new Builder();
    }

    public enum Signature { ES256, ES256_UNKNOWN_KEY, RS256, HS256_WITH_PUBLIC_KEY, NONE }

    public final class Builder {
        private String subject = "account-1";
        private String issuer = ISSUER;
        private List<String> audience = List.of(AUDIENCE);
        private Instant expiresAt = Instant.now().plusSeconds(300);
        private Instant notBefore;
        private String typ = ACCESS_TOKEN_TYPE;
        private Signature signature = Signature.ES256;

        public Builder subject(String subject) { this.subject = subject; return this; }
        public Builder issuer(String issuer) { this.issuer = issuer; return this; }
        public Builder audience(String... audience) { this.audience = List.of(audience); return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder notBefore(Instant notBefore) { this.notBefore = notBefore; return this; }
        public Builder typ(String typ) { this.typ = typ; return this; }
        public Builder signature(Signature signature) { this.signature = signature; return this; }

        public String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder()
                    .issuer(issuer).audience(audience).issueTime(new Date());
            if (subject != null) claims.subject(subject);
            if (expiresAt != null) claims.expirationTime(Date.from(expiresAt));
            if (notBefore != null) claims.notBeforeTime(Date.from(notBefore));
            JWTClaimsSet set = claims.build();
            try {
                return switch (signature) {
                    case NONE -> new PlainJWT(set).serialize();
                    case ES256 -> sign(JWSAlgorithm.ES256, signingKey.getKeyID(), new ECDSASigner(signingKey), set);
                    case ES256_UNKNOWN_KEY -> sign(JWSAlgorithm.ES256, unknownKey.getKeyID(), new ECDSASigner(unknownKey), set);
                    case RS256 -> sign(JWSAlgorithm.RS256, rsaKey.getKeyID(), new RSASSASigner(rsaKey), set);
                    // Alg confusion: the published key bytes used as an HMAC secret, with the published kid.
                    case HS256_WITH_PUBLIC_KEY -> sign(JWSAlgorithm.HS256, signingKey.getKeyID(),
                            new MACSigner(signingKey.toPublicJWK().toJSONString().getBytes(StandardCharsets.UTF_8)), set);
                };
            } catch (Exception e) {
                throw new IllegalStateException("test token", e);
            }
        }

        private String sign(JWSAlgorithm alg, String kid, JWSSigner signer, JWTClaimsSet set) throws Exception {
            JWSHeader.Builder header = new JWSHeader.Builder(alg).keyID(kid);
            if (typ != null) header.type(new JOSEObjectType(typ));
            SignedJWT jwt = new SignedJWT(header.build(), set);
            jwt.sign(signer);
            return jwt.serialize();
        }
    }
}
