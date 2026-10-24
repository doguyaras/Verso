package com.verso.platform.security.authority;

import java.util.List;

/**
 * Verso's application roles (ADR-0019). The names are the role values the identity provider issues: a Keycloak realm
 * (or client) role, an Entra ID app role value. Constants, so that {@code @PreAuthorize} can use them.
 *
 * <ul>
 *   <li>{@link #USER}: every authenticated caller, whether or not the token names a role; a user without any Verso
 *       role keeps today's rights (ADR-0015).</li>
 *   <li>{@link #ADMIN}: administrative endpoints (documents of every account, dashboards); only when the token names
 *       it. Admin implies user, since every caller is a user.</li>
 * </ul>
 */
public final class VersoRoles {

    public static final String ADMIN = "verso-admin";
    public static final String USER = "verso-user";
    /** For {@code @PreAuthorize}: the role prefix comes from {@code GrantedAuthorityDefaults} (role-prefix). */
    public static final String HAS_ADMIN = "hasRole('" + ADMIN + "')";

    /** Every Verso role, highest first. */
    public static final List<String> ALL = List.of(ADMIN, USER);

    private VersoRoles() {}
}
