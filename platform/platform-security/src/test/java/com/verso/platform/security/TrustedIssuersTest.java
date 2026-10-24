package com.verso.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.platform.security.authority.IssuerAuthoritiesConverter;
import com.verso.platform.security.authority.VersoAuthorityProperties;
import com.verso.platform.security.jwt.JwtValidation;
import com.verso.platform.security.jwt.TrustedIssuer;
import com.verso.platform.security.jwt.TrustedIssuers;
import com.verso.platform.security.jwt.VersoJwtProperties;
import com.verso.platform.security.testing.TestEntraProvider;
import com.verso.platform.security.testing.TestEntraProvider.Signature;
import com.verso.platform.security.testing.TestIdentityProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Per-issuer trust (ADR-0041): Microsoft Entra ID v2.0 tokens next to the bundled Keycloak's, against generated keys and
 * stub JWKS servers. Every Entra rule has a refusal here, the Keycloak rules are shown unchanged, and the MCP audience
 * stays separate for both issuers (ADR-0031).
 */
class TrustedIssuersTest {

    private static final TestIdentityProvider KEYCLOAK = TestIdentityProvider.get();
    private static final TestEntraProvider ENTRA = TestEntraProvider.get();
    private static final VersoAuthorityProperties AUTHORITIES =
            new VersoAuthorityProperties("realm_access.roles", "", "groups", "ROLE_", "GROUP_");
    private static final String GROUP = "8c1b7e7e-1d4b-4f3a-9a77-2f0c5f3b6d10";

    private final VersoJwtProperties properties = properties(true, entra(ENTRA.jwkSetUri()));
    private final TrustedIssuers issuers = TrustedIssuers.of(properties, AUTHORITIES);
    private final JwtDecoder api = JwtValidation.decoder(issuers.all(), TrustedIssuer::audiences, properties, Clock.systemUTC());
    private final JwtDecoder mcp = JwtValidation.decoder(issuers.forMcp("verso-mcp"), TrustedIssuer::mcpAudiences,
            properties, Clock.systemUTC());

    // --- accepted ------------------------------------------------------------------------------------------------------

    @Test
    void entraToken_whenV2RS256ForTheTenantAndTheApi_isAcceptedWithTheObjectIdAsAccount() {
        Jwt jwt = api.decode(ENTRA.token().roles("verso-admin").groups(GROUP).build());
        assertThat(jwt.getSubject()).as("oid, not the pairwise sub").isEqualTo("11111111-2222-4333-8444-555555555555");
        assertThat(authorities(jwt)).contains("ROLE_verso-user", "ROLE_verso-admin", "GROUP_" + GROUP);
        // The Application ID URI form of the audience is accepted as well.
        assertThat(api.decode(ENTRA.token().audience("api://verso-api").build()).getSubject()).isNotBlank();
    }

    @Test
    void keycloakToken_whenEntraIsAlsoTrusted_isUnchanged() {
        Jwt jwt = api.decode(KEYCLOAK.token().subject("acct-kc").realmRoles("verso-admin").build());
        assertThat(jwt.getSubject()).isEqualTo("acct-kc");
        assertThat(authorities(jwt)).contains("ROLE_verso-admin");
        // Entra's claim paths are never read from a Keycloak token, nor Keycloak's from an Entra token.
        Jwt entraShaped = api.decode(KEYCLOAK.token().subject("acct-kc2").claim("roles", List.of("verso-admin")).build());
        assertThat(authorities(entraShaped)).doesNotContain("ROLE_verso-admin");
        Jwt keycloakShaped = api.decode(ENTRA.token().claim("realm_access", Map.of("roles", List.of("verso-admin"))).build());
        assertThat(authorities(keycloakShaped)).doesNotContain("ROLE_verso-admin");
        // Keycloak stays ES256 only: an RS256 token with a key Keycloak's own JWKS publishes is still refused.
        assertRefused(api, KEYCLOAK.token().signature(TestIdentityProvider.Signature.RS256).build());
        assertRefused(api, KEYCLOAK.token().typ("JWT").build());
    }

    // --- refused -------------------------------------------------------------------------------------------------------

    @Test
    void entraToken_whenAnyRuleIsBroken_isRefused() {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("other tenant", ENTRA.token().tenant(TestEntraProvider.OTHER_TENANT).build());
        refused.put("no tenant", ENTRA.token().tenant(null).build());
        refused.put("other audience", ENTRA.token().audience("00000003-0000-0000-c000-000000000000").build());
        refused.put("v1.0 token", ENTRA.token().version("1.0").build());
        refused.put("v1.0 issuer", ENTRA.token().version("1.0").issuer("https://sts.windows.net/" + TestEntraProvider.TENANT + "/").build());
        refused.put("multi-tenant issuer", ENTRA.token().issuer(TestEntraProvider.AUTHORITY + "/common/v2.0").build());
        refused.put("ES256 under the Entra issuer", ENTRA.token().signature(Signature.ES256).build());
        refused.put("RS256 with an EC key id", ENTRA.token().signature(Signature.RS256_WITH_EC_KEY_ID).build());
        refused.put("HS256 with the public key", ENTRA.token().signature(Signature.HS256_WITH_PUBLIC_KEY).build());
        refused.put("alg none", ENTRA.token().signature(Signature.NONE).build());
        refused.put("typ at+jwt", ENTRA.token().typ("at+jwt").build());
        refused.put("no typ", ENTRA.token().typ(null).build());
        refused.put("expired", ENTRA.token().expiresAt(Instant.now().minusSeconds(120)).build());
        refused.put("ID token shape (no scp)", ENTRA.token().scope(null).build());
        refused.put("no oid", ENTRA.token().objectId(null).build());
        refused.put("oid not an account id", ENTRA.token().objectId("a/b").build());
        refused.put("MCP audience on the API", ENTRA.token().audience(TestEntraProvider.MCP_AUDIENCE).build());
        refused.forEach((name, token) -> assertThatThrownBy(() -> api.decode(token)).as(name)
                .isInstanceOf(BadJwtException.class));
    }

    @Test
    void unknownIssuer_whenTokenArrives_isRefusedWithoutAnyKeyFetch() {
        int keycloakFetches = KEYCLOAK.jwksRequests();
        int entraFetches = ENTRA.jwksRequests();
        JwtDecoder fresh = JwtValidation.decoder(TrustedIssuers.of(properties, AUTHORITIES).all(),
                TrustedIssuer::audiences, properties, Clock.systemUTC());
        for (String issuer : List.of("https://evil.example/" + TestEntraProvider.TENANT + "/v2.0",
                ENTRA.jwkSetUri(), KEYCLOAK.jwkSetUri(), TestIdentityProvider.ISSUER + "/")) {
            assertThatThrownBy(() -> fresh.decode(ENTRA.token().issuer(issuer).build())).as(issuer)
                    .isInstanceOf(BadJwtException.class).hasMessageContaining("issuer is not trusted");
        }
        assertThatThrownBy(() -> fresh.decode("not.a.jwt")).isInstanceOf(BadJwtException.class);
        assertThat(KEYCLOAK.jwksRequests()).as("no fetch for an unknown issuer").isEqualTo(keycloakFetches);
        assertThat(ENTRA.jwksRequests()).as("no fetch for an unknown issuer").isEqualTo(entraFetches);
    }

    /** A token signed by one issuer's key but naming the other issuer is checked against the named issuer's rules. */
    @Test
    void crossIssuer_whenKeysAndIssuerDoNotBelongTogether_isRefused() {
        assertRefused(api, ENTRA.token().issuer(TestIdentityProvider.ISSUER).audience("verso-api").typ("at+jwt").build());
        assertRefused(api, KEYCLOAK.token().issuer(TestEntraProvider.ISSUER).audience(TestEntraProvider.API_AUDIENCE)
                .typ("JWT").claim("ver", "2.0").claim("tid", TestEntraProvider.TENANT).claim("scp", "access_as_user")
                .claim("oid", "11111111-2222-4333-8444-555555555555").build());
    }

    // --- groups overage ------------------------------------------------------------------------------------------------

    @Test
    void groupsOverage_whenEntraLeavesTheGroupsOut_grantsNoGroupAndCountsIt() {
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        IssuerAuthoritiesConverter converter = issuers.authoritiesConverter(meters);
        // Even a groups claim next to the overage pointer is not trusted: the IdP itself says the list is incomplete.
        Jwt jwt = api.decode(ENTRA.token().roles("verso-admin").groups(GROUP).groupsOverage().build());
        List<String> authorities = converter.convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
        assertThat(authorities).contains("ROLE_verso-user", "ROLE_verso-admin").noneMatch(a -> a.startsWith("GROUP_"));
        Jwt hasGroups = api.decode(ENTRA.token().claim("hasgroups", true).groups(GROUP).build());
        assertThat(converter.convert(hasGroups)).extracting(GrantedAuthority::getAuthority).noneMatch(a -> a.startsWith("GROUP_"));
        assertThat(meters.get(IssuerAuthoritiesConverter.OVERAGE_METRIC).tag("issuer", "entra").counter().count())
                .isEqualTo(2.0);
        // Keycloak tokens are never read for Entra's marker.
        Jwt keycloak = api.decode(KEYCLOAK.token().claim("groups", List.of("/hukuk"))
                .claim("_claim_names", Map.of("groups", "src1")).build());
        assertThat(converter.convert(keycloak)).extracting(GrantedAuthority::getAuthority).contains("GROUP_/hukuk");
    }

    /** The overage WARN names the reason only: no group id, no account, no token (security-rules 4). */
    @Test
    void groupsOverage_whenLogged_carriesNoClaimValue() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(com.verso.platform.security.authority.ClaimAuthoritiesConverter.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender =
                new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            String token = ENTRA.token().objectId("99999999-8888-4777-8666-555555555555").groups("SENSITIVE-MARKER-group")
                    .groupsOverage().build();
            issuers.authoritiesConverter(new SimpleMeterRegistry()).convert(api.decode(token));
            assertThat(appender.list).extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .containsExactly("Authority claim rejected: code=GROUPS_OVERAGE reason=GROUPS_NOT_IN_TOKEN claim=groups")
                    .noneMatch(m -> m.contains("SENSITIVE-MARKER") || m.contains("99999999") || m.contains(token));
        } finally {
            logger.detachAppender(appender);
            appender.stop();
        }
    }

    // --- MCP audience separation (ADR-0031) ------------------------------------------------------------------------------

    @Test
    void mcpAudience_whenTokensCrossSurfaces_isSeparateForBothIssuers() {
        String entraMcp = ENTRA.token().audience(TestEntraProvider.MCP_AUDIENCE).build();
        String entraApi = ENTRA.token().build();
        assertThat(mcp.decode(entraMcp).getSubject()).isEqualTo("11111111-2222-4333-8444-555555555555");
        assertRefused(mcp, entraApi);
        assertRefused(api, entraMcp);

        String keycloakMcp = KEYCLOAK.token().subject("acct-mcp").audience("verso-mcp").build();
        String keycloakApi = KEYCLOAK.tokenFor("acct-api");
        assertThat(mcp.decode(keycloakMcp).getSubject()).isEqualTo("acct-mcp");
        assertRefused(mcp, keycloakApi);
        assertRefused(api, keycloakMcp);
        // Entra's MCP audience means nothing under the Keycloak issuer, and the reverse.
        assertRefused(mcp, KEYCLOAK.token().audience(TestEntraProvider.MCP_AUDIENCE).build());
        assertRefused(mcp, ENTRA.token().audience("verso-mcp").build());
    }

    @Test
    void mcp_whenEntraHasNoMcpAudience_trustsOnlyThePrimaryIssuer() {
        VersoJwtProperties noMcp = properties(true, new VersoJwtProperties.Entra(TestEntraProvider.TENANT,
                TestEntraProvider.AUTHORITY, ENTRA.jwkSetUri(), List.of(TestEntraProvider.API_AUDIENCE), List.of(), "roles", "groups"));
        TrustedIssuers only = TrustedIssuers.of(noMcp, AUTHORITIES);
        assertThat(only.forMcp("verso-mcp")).extracting(TrustedIssuer::name).containsExactly(TrustedIssuers.PRIMARY);
    }

    // --- configuration -------------------------------------------------------------------------------------------------

    @Test
    void entraOnly_whenThePrimaryIssuerIsLeftEmpty_trustsEntraAlone() {
        VersoJwtProperties entraOnly = properties(false, entra(ENTRA.jwkSetUri()));
        TrustedIssuers only = TrustedIssuers.of(entraOnly, AUTHORITIES);
        assertThat(only.all()).extracting(TrustedIssuer::name).containsExactly(TrustedIssuers.ENTRA);
        JwtDecoder decoder = JwtValidation.decoder(only.all(), TrustedIssuer::audiences, entraOnly, Clock.systemUTC());
        assertThat(decoder.decode(ENTRA.token().build()).getSubject()).isNotBlank();
        assertRefused(decoder, KEYCLOAK.tokenFor("acct-kc"));
        assertThatThrownBy(() -> properties(false, VersoJwtProperties.Entra.DISABLED))
                .hasMessageContaining("issuer is required");
    }

    @Test
    void entraIssuer_isDerivedFromTheTenantAndAlgorithmPinnedToRs256() {
        TrustedIssuer entra = issuers.all().stream().filter(i -> i.name().equals(TrustedIssuers.ENTRA)).findFirst().orElseThrow();
        assertThat(entra.issuer()).isEqualTo(TestEntraProvider.ISSUER);
        assertThat(entra.algorithms()).containsExactly(SignatureAlgorithm.RS256);
        assertThat(entra.typeHeader()).isEqualTo("JWT");
        assertThat(entra.requiredClaims()).containsEntry("ver", "2.0").containsEntry("tid", TestEntraProvider.TENANT);
        assertThat(new VersoJwtProperties.Entra(TestEntraProvider.TENANT, "https://login.microsoftonline.us/", "",
                List.of("a"), List.of(), "roles", "groups").keySetUri())
                .isEqualTo(URI.create("https://login.microsoftonline.us/" + TestEntraProvider.TENANT + "/discovery/v2.0/keys"));
    }

    @Test
    void entraSettings_whenUnsafeOrIncomplete_stopTheStart() {
        for (String tenant : List.of("common", "organizations", "consumers", "9188040d-6c67-4c5b-b112-36a304b66dad", "x")) {
            assertThatThrownBy(() -> new VersoJwtProperties.Entra(tenant, TestEntraProvider.AUTHORITY, "",
                    List.of("a"), List.of(), "roles", "groups")).as(tenant).hasMessageContaining("tenant-id");
        }
        for (String authority : List.of("http://login.microsoftonline.com", "https://login.microsoftonline.com/common",
                "https://user@login.microsoftonline.com", "https://login.microsoftonline.com?x=1")) {
            assertThatThrownBy(() -> new VersoJwtProperties.Entra(TestEntraProvider.TENANT, authority, "",
                    List.of("a"), List.of(), "roles", "groups")).as(authority).hasMessageContaining("authority");
        }
        assertThatThrownBy(() -> new VersoJwtProperties.Entra(TestEntraProvider.TENANT, TestEntraProvider.AUTHORITY, "",
                List.of(" "), List.of(), "roles", "groups")).hasMessageContaining("audiences is required");
        assertThatThrownBy(() -> new VersoJwtProperties.Entra(TestEntraProvider.TENANT, TestEntraProvider.AUTHORITY, "",
                List.of("a", "b"), List.of("b"), "roles", "groups")).hasMessageContaining("mcp-audiences");
        assertThatThrownBy(() -> properties(true, entra("http://keys.example/keys"))).hasMessageContaining("entra.jwk-set-uri");
        assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", "a..b", "ROLE_", "GROUP_"))
                .hasMessageContaining("groups-claim");
    }

    // --- helpers -------------------------------------------------------------------------------------------------------

    private static VersoJwtProperties.Entra entra(String jwkSetUri) {
        return new VersoJwtProperties.Entra(TestEntraProvider.TENANT, TestEntraProvider.AUTHORITY, jwkSetUri,
                List.of(TestEntraProvider.API_AUDIENCE, "api://verso-api"), List.of(TestEntraProvider.MCP_AUDIENCE),
                "roles", "groups");
    }

    private static VersoJwtProperties properties(boolean primary, VersoJwtProperties.Entra entra) {
        return new VersoJwtProperties(primary ? TestIdentityProvider.ISSUER : "",
                primary ? URI.create(KEYCLOAK.jwkSetUri()) : null, "verso-api", Duration.ofSeconds(30),
                TestIdentityProvider.ACCESS_TOKEN_TYPE, Duration.ofSeconds(2), false, List.of(SignatureAlgorithm.ES256), entra);
    }

    private List<String> authorities(Jwt jwt) {
        return issuers.authoritiesConverter(null).convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static void assertRefused(JwtDecoder decoder, String token) {
        assertThatThrownBy(() -> decoder.decode(token)).isInstanceOf(BadJwtException.class);
    }
}
