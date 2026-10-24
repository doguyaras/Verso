package com.verso.platform.security.authority;

import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Where the access token carries roles and groups, and how they become Spring authorities (ADR-0019). The paths are
 * per identity provider, so nothing here is Keycloak-specific: the defaults are the bundled Keycloak's shape, Entra ID
 * uses {@code roles} and {@code groups} (ADR-0019 lists the mappings).
 *
 * <p>A path is a dot-separated walk through nested JSON objects ({@code realm_access.roles} is
 * {@code {"realm_access": {"roles": [...]}}}); a claim name that itself contains a dot cannot be addressed.
 *
 * @param rolesClaim       path of the role list; required (Keycloak realm roles, Entra app roles)
 * @param clientRolesClaim optional path of a second role list, e.g. Keycloak client roles
 *                         {@code resource_access.verso-api.roles}; empty when unused
 * @param groupsClaim      optional path of the group list (Keycloak group paths, Entra group object ids); empty when
 *                         unused
 * @param rolePrefix       authority prefix of a role; {@code hasRole(...)} uses the same prefix
 * @param groupPrefix      authority prefix of a group; distinct from the role prefix, so a group named like a role
 *                         never becomes that role
 */
@ConfigurationProperties("verso.security.authorities")
public record VersoAuthorityProperties(
        @DefaultValue("realm_access.roles") String rolesClaim,
        @DefaultValue("") String clientRolesClaim,
        @DefaultValue("groups") String groupsClaim,
        @DefaultValue("ROLE_") String rolePrefix,
        @DefaultValue("GROUP_") String groupPrefix) {

    /** One or more claim names separated by single dots; no empty segment. */
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9_:$-]+(\\.[A-Za-z0-9_:$-]+)*");
    /** Upper case, ending in "_", so that a prefix and a value cannot run into each other. */
    private static final Pattern PREFIX = Pattern.compile("[A-Z][A-Z0-9_]*_");
    /** Prefixes Spring Security itself grants (scopes, authentication factors); a claim must not mint those. */
    private static final Set<String> RESERVED_PREFIXES = Set.of("SCOPE_", "FACTOR_");

    public VersoAuthorityProperties {
        rolesClaim = trim(rolesClaim);
        clientRolesClaim = trim(clientRolesClaim);
        groupsClaim = trim(groupsClaim);
        if (rolesClaim.isEmpty()) {
            throw new IllegalStateException("verso.security.authorities.roles-claim is required");
        }
        requirePath(rolesClaim, "roles-claim");
        if (!clientRolesClaim.isEmpty()) requirePath(clientRolesClaim, "client-roles-claim");
        if (!groupsClaim.isEmpty()) requirePath(groupsClaim, "groups-claim");
        requirePrefix(rolePrefix, "role-prefix");
        requirePrefix(groupPrefix, "group-prefix");
        if (rolePrefix.startsWith(groupPrefix) || groupPrefix.startsWith(rolePrefix)) {
            throw new IllegalStateException(
                    "verso.security.authorities.role-prefix and group-prefix must not overlap");
        }
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }

    private static void requirePath(String path, String name) {
        if (!PATH.matcher(path).matches()) {
            throw new IllegalStateException("verso.security.authorities." + name + " is not a dotted claim path");
        }
    }

    private static void requirePrefix(String prefix, String name) {
        if (prefix == null || !PREFIX.matcher(prefix).matches()) {
            throw new IllegalStateException("verso.security.authorities." + name
                    + " must be upper case letters, digits or '_', ending in '_' (e.g. ROLE_)");
        }
        if (RESERVED_PREFIXES.stream().anyMatch(r -> prefix.startsWith(r) || r.startsWith(prefix))) {
            throw new IllegalStateException("verso.security.authorities." + name + " overlaps a Spring Security prefix");
        }
    }
}
