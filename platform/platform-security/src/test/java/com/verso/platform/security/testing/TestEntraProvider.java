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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A stand-in for Microsoft Entra ID (ADR-0041): an RSA signing key published as JWKS by the JDK's HTTP server, and a
 * builder of v2.0 access tokens in Entra's shape ({@code ver}, {@code tid}, {@code oid}, a pairwise {@code sub},
 * {@code scp}, {@code roles}, {@code groups}, JOSE {@code typ: JWT}). The JWKS also lists an EC key, so an ES256 token
 * with a published key id is a real alg-confusion attempt under the Entra issuer. One provider per JVM, never stopped.
 */
public final class TestEntraProvider {

    public static final String TENANT = "00000000-0000-4000-8000-00000000aaaa";
    public static final String OTHER_TENANT = "00000000-0000-4000-8000-00000000bbbb";
    public static final String AUTHORITY = "https://login.microsoftonline.com";
    public static final String ISSUER = AUTHORITY + "/" + TENANT + "/v2.0";
    /** The API's application (client) id, as v2.0 tokens carry it in aud. */
    public static final String API_AUDIENCE = "00000000-0000-4000-8000-0000000000a1";
    /** The MCP resource's own application id (ADR-0031). */
    public static final String MCP_AUDIENCE = "00000000-0000-4000-8000-0000000000b2";

    private static final TestEntraProvider INSTANCE = new TestEntraProvider();

    private final RSAKey signingKey;
    private final ECKey ecKey;
    private final HttpServer server;
    private final AtomicInteger jwksRequests = new AtomicInteger();

    private TestEntraProvider() {
        try {
            signingKey = new RSAKeyGenerator(2048).keyID("entra-rs256").generate();
            ecKey = new ECKeyGenerator(Curve.P_256).keyID("entra-es256").generate();
            byte[] jwks = new JWKSet(List.of(signingKey.toPublicJWK(), ecKey.toPublicJWK())).toString()
                    .getBytes(StandardCharsets.UTF_8);
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            server.createContext("/keys", exchange -> {
                jwksRequests.incrementAndGet();
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, jwks.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(jwks);
                }
            });
            server.start();
        } catch (Exception e) {
            throw new IllegalStateException("test Entra provider", e);
        }
    }

    public static TestEntraProvider get() {
        return INSTANCE;
    }

    public String jwkSetUri() {
        return "http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/keys";
    }

    public int jwksRequests() {
        return jwksRequests.get();
    }

    /** The Spring properties that add this provider as the Entra issuer, next to the primary one. */
    public String[] propertyPairs() {
        return new String[]{
                "verso.security.jwt.entra.tenant-id=" + TENANT,
                "verso.security.jwt.entra.jwk-set-uri=" + jwkSetUri(),
                "verso.security.jwt.entra.audiences=" + API_AUDIENCE + ",api://verso-api",
                "verso.security.jwt.entra.mcp-audiences=" + MCP_AUDIENCE};
    }

    public Builder token() {
        return new Builder();
    }

    public enum Signature { RS256, RS256_WITH_EC_KEY_ID, ES256, HS256_WITH_PUBLIC_KEY, NONE }

    public final class Builder {
        private String issuer = ISSUER;
        private String audience = API_AUDIENCE;
        private String objectId = "11111111-2222-4333-8444-555555555555";
        private String pairwiseSubject = "AAAAAAAAAAAAAAAAAAAAAIkzqFVrSaSaFHy782bbtaQ";
        private String tenant = TENANT;
        private String version = "2.0";
        private String scope = "access_as_user";
        private String typ = "JWT";
        private Instant expiresAt = Instant.now().plusSeconds(3600);
        private Signature signature = Signature.RS256;
        private final Map<String, Object> extra = new LinkedHashMap<>();

        public Builder issuer(String issuer) { this.issuer = issuer; return this; }
        public Builder audience(String audience) { this.audience = audience; return this; }
        public Builder objectId(String objectId) { this.objectId = objectId; return this; }
        public Builder pairwiseSubject(String sub) { this.pairwiseSubject = sub; return this; }
        public Builder tenant(String tenant) { this.tenant = tenant; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder scope(String scope) { this.scope = scope; return this; }
        public Builder typ(String typ) { this.typ = typ; return this; }
        public Builder expiresAt(Instant expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder signature(Signature signature) { this.signature = signature; return this; }
        public Builder roles(String... roles) { return claim("roles", List.of(roles)); }
        public Builder groups(String... groups) { return claim("groups", List.of(groups)); }
        /** Entra's groups overage: more than 200 groups, so the claim points at Microsoft Graph instead. */
        public Builder groupsOverage() {
            claim("_claim_names", Map.of("groups", "src1"));
            return claim("_claim_sources", Map.of("src1", Map.of("endpoint",
                    "https://graph.microsoft.com/v1.0/users/" + objectId + "/getMemberObjects")));
        }
        public Builder claim(String name, Object value) { extra.put(name, value); return this; }

        public String build() {
            JWTClaimsSet.Builder claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience)
                    .issueTime(new Date()).notBeforeTime(new Date()).expirationTime(Date.from(expiresAt));
            if (pairwiseSubject != null) claims.subject(pairwiseSubject);
            if (objectId != null) claims.claim("oid", objectId);
            if (tenant != null) claims.claim("tid", tenant);
            if (version != null) claims.claim("ver", version);
            if (scope != null) claims.claim("scp", scope);
            claims.claim("azp", "00000000-0000-4000-8000-0000000000c3");
            extra.forEach(claims::claim);
            JWTClaimsSet set = claims.build();
            try {
                return switch (signature) {
                    case NONE -> new PlainJWT(set).serialize();
                    case RS256 -> sign(JWSAlgorithm.RS256, signingKey.getKeyID(), new RSASSASigner(signingKey), set);
                    // An RS256 signature under the kid of the published EC key: the key type must match the algorithm.
                    case RS256_WITH_EC_KEY_ID -> sign(JWSAlgorithm.RS256, ecKey.getKeyID(), new RSASSASigner(signingKey), set);
                    case ES256 -> sign(JWSAlgorithm.ES256, ecKey.getKeyID(), new ECDSASigner(ecKey), set);
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
