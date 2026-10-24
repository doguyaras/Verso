package com.verso.platform.security.authority;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The caller's Verso roles (ADR-0019), for a response that tells a client what the caller may do (the panel hides
 * what a role cannot use). Only Verso's own roles leave the server: the IdP's other roles and the groups are the
 * customer's directory data and are not echoed. The server's checks use the authorities, not this list.
 */
public final class CallerRoles {

    private final String rolePrefix;

    public CallerRoles(VersoAuthorityProperties properties) {
        this.rolePrefix = properties.rolePrefix();
    }

    /** The current request's Verso roles, highest first; empty without an authenticated access token. */
    public List<String> current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    public List<String> of(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) return List.of();
        Collection<GrantedAuthority> authorities = jwt.getAuthorities();
        Set<String> names = authorities.stream().map(GrantedAuthority::getAuthority).collect(Collectors.toSet());
        return VersoRoles.ALL.stream().filter(role -> names.contains(rolePrefix + role)).toList();
    }
}
