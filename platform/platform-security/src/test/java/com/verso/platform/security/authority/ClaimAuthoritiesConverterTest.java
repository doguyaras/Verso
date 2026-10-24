package com.verso.platform.security.authority;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** ADR-0019: token roles and groups as authorities, for Keycloak's and Entra ID's token shapes, failing closed. */
class ClaimAuthoritiesConverterTest {

    private static final VersoAuthorityProperties KEYCLOAK =
            new VersoAuthorityProperties("realm_access.roles", "", "groups", "ROLE_", "GROUP_");
    private static final VersoAuthorityProperties KEYCLOAK_WITH_CLIENT_ROLES =
            new VersoAuthorityProperties("realm_access.roles", "resource_access.verso-api.roles", "groups", "ROLE_", "GROUP_");
    private static final VersoAuthorityProperties ENTRA =
            new VersoAuthorityProperties("roles", "", "groups", "ROLE_", "GROUP_");

    private Logger logger;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger(ClaimAuthoritiesConverter.class);
        appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    @Test
    void convert_whenKeycloakTokenNamesAdminAndGroups_grantsRolesGroupsAndTheDefaultUser() {
        Jwt jwt = jwt(claims -> {
            claims.put("realm_access", Map.of("roles", List.of("verso-admin", "offline_access", "default-roles-verso")));
            claims.put("groups", List.of("/hukuk", "/hukuk/sozlesmeler"));
            claims.put("scope", "openid profile");
        });

        assertThat(authorities(KEYCLOAK, jwt)).containsExactlyInAnyOrder(
                "SCOPE_openid", "SCOPE_profile",
                "ROLE_verso-user", "ROLE_verso-admin", "ROLE_offline_access", "ROLE_default-roles-verso",
                "GROUP_/hukuk", "GROUP_/hukuk/sozlesmeler");
        assertThat(roles(KEYCLOAK, jwt)).containsExactly(VersoRoles.ADMIN, VersoRoles.USER);
        assertThat(appender.list).isEmpty();
    }

    @Test
    void convert_whenKeycloakClientRolesPathIsSet_readsThemAsRoles() {
        Jwt jwt = jwt(claims -> {
            claims.put("realm_access", Map.of("roles", List.of("offline_access")));
            claims.put("resource_access", Map.of(
                    "verso-api", Map.of("roles", List.of("verso-admin")),
                    "other-app", Map.of("roles", List.of("other-admin"))));
        });

        assertThat(authorities(KEYCLOAK_WITH_CLIENT_ROLES, jwt)).contains("ROLE_verso-admin").doesNotContain("ROLE_other-admin");
        assertThat(authorities(KEYCLOAK, jwt)).as("client roles unread unless configured").doesNotContain("ROLE_verso-admin");
    }

    @Test
    void convert_whenEntraTokenCarriesAppRolesAndGroupIds_grantsThem() {
        Jwt jwt = jwt(claims -> {
            claims.put("roles", List.of("verso-admin"));
            claims.put("groups", List.of("8c1b7e7e-1d4b-4f3a-9a77-2f0c5f3b6d10"));
            claims.put("scp", "access_as_user");
        });

        assertThat(authorities(ENTRA, jwt)).containsExactlyInAnyOrder("SCOPE_access_as_user", "ROLE_verso-user",
                "ROLE_verso-admin", "GROUP_8c1b7e7e-1d4b-4f3a-9a77-2f0c5f3b6d10");
        assertThat(roles(ENTRA, jwt)).containsExactly(VersoRoles.ADMIN, VersoRoles.USER);
        assertThat(roles(KEYCLOAK, jwt)).as("an Entra token read with Keycloak's paths: no admin").containsExactly(VersoRoles.USER);
    }

    /** AD FS and some other IdPs send a single role as a string instead of a one-element list. */
    @Test
    void convert_whenRolesClaimIsASingleString_readsItAsOneRole() {
        assertThat(roles(ENTRA, jwt(claims -> claims.put("roles", "verso-admin")))).containsExactly(VersoRoles.ADMIN, VersoRoles.USER);
    }

    /** Today's users keep their rights: no Verso role in the token, or no role claim at all, is a verso-user. */
    @Test
    void convert_whenClaimsAreMissing_grantsOnlyTheDefaultUser() {
        Jwt bare = jwt(claims -> {});
        assertThat(authorities(KEYCLOAK, bare)).containsExactly("ROLE_verso-user");
        assertThat(authorities(ENTRA, bare)).containsExactly("ROLE_verso-user");
        assertThat(roles(KEYCLOAK, bare)).containsExactly(VersoRoles.USER);

        Jwt noRolesInside = jwt(claims -> claims.put("realm_access", Map.of("other", List.of("verso-admin"))));
        assertThat(roles(KEYCLOAK, noRolesInside)).containsExactly(VersoRoles.USER);
        assertThat(appender.list).as("a missing claim is normal, not a fault").isEmpty();
    }

    /** Malformed claims never reject the token and never grant anything; the values never reach the log. */
    @Test
    void convert_whenClaimsAreMalformed_skipsThemAndLogsTheKindOnly() {
        List<Jwt> malformed = List.of(
                jwt(c -> c.put("realm_access", "verso-admin")),                         // not an object
                jwt(c -> c.put("realm_access", List.of(Map.of("roles", List.of("verso-admin"))))),
                jwt(c -> c.put("realm_access", Map.of("roles", Map.of("0", "verso-admin")))), // not a list
                jwt(c -> c.put("realm_access", Map.of("roles", 42))),
                jwt(c -> c.put("realm_access", Map.of("roles", true))));
        ClaimAuthoritiesConverter converter = new ClaimAuthoritiesConverter(KEYCLOAK);
        for (Jwt jwt : malformed) {
            assertThat(converter.convert(jwt)).as(jwt.getClaims().toString()).extracting(GrantedAuthority::getAuthority)
                    .containsExactly("ROLE_verso-user");
        }
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage).containsExactlyInAnyOrder(
                "Authority claim rejected: code=MALFORMED_CLAIM reason=NOT_AN_OBJECT claim=roles",
                "Authority claim rejected: code=MALFORMED_CLAIM reason=NOT_A_LIST claim=roles");
        assertThat(appender.list).allSatisfy(e -> assertThat(e.getFormattedMessage()).doesNotContain("verso-admin"));
    }

    @Test
    void convert_whenSomeValuesAreInvalid_keepsOnlyTheValidOnes() {
        List<Object> values = new ArrayList<>(List.of("verso-admin", "", "   ", "a".repeat(257), "line\nbreak",
                "bidi‮override", 7, List.of("nested"), Map.of("k", "v")));
        values.add(null);
        Jwt jwt = jwt(c -> c.put("groups", values));

        assertThat(authorities(ENTRA, jwt)).containsExactlyInAnyOrder("ROLE_verso-user", "GROUP_verso-admin");
        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Authority claim rejected: code=MALFORMED_CLAIM reason=INVALID_VALUE claim=groups");
    }

    @Test
    void convert_whenAClaimHasTooManyValues_readsABoundedNumber() {
        List<String> groups = IntStream.range(0, 1000).mapToObj(i -> "g" + i).toList();
        Collection<String> authorities = authorities(ENTRA, jwt(c -> c.put("groups", groups)));
        assertThat(authorities).hasSize(1 + ClaimAuthoritiesConverter.MAX_VALUES_PER_CLAIM).contains("GROUP_g0")
                .doesNotContain("GROUP_g999");
        assertThat(appender.list).singleElement().extracting(ILoggingEvent::getFormattedMessage)
                .isEqualTo("Authority claim rejected: code=MALFORMED_CLAIM reason=TOO_MANY_VALUES claim=groups");
    }

    /** A group cannot become a role, whatever its name: the prefixes keep them apart. */
    @Test
    void convert_whenAGroupIsNamedLikeARole_grantsNoRole() {
        Jwt jwt = jwt(c -> c.put("groups", List.of("verso-admin", "ROLE_verso-admin")));
        assertThat(roles(KEYCLOAK, jwt)).containsExactly(VersoRoles.USER);
        assertThat(authorities(KEYCLOAK, jwt)).doesNotContain("ROLE_verso-admin");
    }

    /** The same misconfigured claim on every request is logged once, not once per request. */
    @Test
    void convert_whenTheSameClaimIsMalformedRepeatedly_logsOnce() {
        ClaimAuthoritiesConverter converter = new ClaimAuthoritiesConverter(KEYCLOAK);
        for (int i = 0; i < 5; i++) converter.convert(jwt(c -> c.put("realm_access", "x")));
        assertThat(appender.list).hasSize(1);
    }

    @Test
    void callerRoles_whenNotAJwtAuthentication_areEmpty() {
        assertThat(new CallerRoles(KEYCLOAK).of(null)).isEmpty();
    }

    @Test
    void properties_whenPathsOrPrefixesAreUnsafe_failAtStartup() {
        assertThatThrownBy(() -> new VersoAuthorityProperties(" ", "", "groups", "ROLE_", "GROUP_"))
                .hasMessageContaining("roles-claim is required");
        for (String path : List.of("realm_access..roles", ".roles", "roles.", "realm access.roles", "roles[0]")) {
            assertThatThrownBy(() -> new VersoAuthorityProperties(path, "", "groups", "ROLE_", "GROUP_")).as(path)
                    .hasMessageContaining("roles-claim is not a dotted claim path");
            assertThatThrownBy(() -> new VersoAuthorityProperties("roles", path, "groups", "ROLE_", "GROUP_")).as(path)
                    .hasMessageContaining("client-roles-claim");
            assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", path, "ROLE_", "GROUP_")).as(path)
                    .hasMessageContaining("groups-claim");
        }
        for (String prefix : List.of("", "role_", "ROLE", "SCOPE_", "FACTOR_", "S_X", "ROLE-")) {
            assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", "groups", prefix, "GROUP_")).as(prefix)
                    .hasMessageContaining("role-prefix");
        }
        assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", "groups", "ROLE_", "ROLE_"))
                .hasMessageContaining("must not overlap");
        assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", "groups", "ROLE_", "ROLE_GROUP_"))
                .as("ROLE_GROUP_x would be hasRole('GROUP_x')").hasMessageContaining("must not overlap");
        assertThatThrownBy(() -> new VersoAuthorityProperties("roles", "", "groups", "ROLE_", null))
                .hasMessageContaining("group-prefix");
    }

    @Test
    void properties_whenOptionalPathsAreBlank_readNothingFromThem() {
        VersoAuthorityProperties none = new VersoAuthorityProperties("roles", null, " ", "ROLE_", "GROUP_");
        assertThat(none.clientRolesClaim()).isEmpty();
        assertThat(none.groupsClaim()).isEmpty();
        Jwt jwt = jwt(c -> c.put("groups", List.of("/hukuk")));
        assertThat(authorities(none, jwt)).containsExactly("ROLE_verso-user");
    }

    private static Collection<String> authorities(VersoAuthorityProperties properties, Jwt jwt) {
        return new ClaimAuthoritiesConverter(properties).convert(jwt).stream().map(GrantedAuthority::getAuthority).toList();
    }

    private static List<String> roles(VersoAuthorityProperties properties, Jwt jwt) {
        JwtAuthenticationToken token = new JwtAuthenticationToken(jwt, new ClaimAuthoritiesConverter(properties).convert(jwt));
        return new CallerRoles(properties).of(token);
    }

    private static Jwt jwt(Consumer<Map<String, Object>> claims) {
        return Jwt.withTokenValue("token").header("alg", "ES256").subject("acct-1")
                .claims(claims).build();
    }
}
