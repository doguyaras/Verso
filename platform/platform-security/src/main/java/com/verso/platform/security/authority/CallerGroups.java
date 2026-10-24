package com.verso.platform.security.authority;

import java.util.Collection;
import java.util.List;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * The caller's IdP groups (ADR-0019, ADR-0028), for the access check of shared collections: the values of the groups
 * claim exactly as the token carries them (a Keycloak full path, an Entra object id). They come from the
 * {@code <group-prefix>} authorities, so they are already bounded and validated by {@link ClaimAuthoritiesConverter}: a
 * malformed claim produced no group authority and therefore no group here (fail closed). Groups are the customer's
 * directory data: they decide access and are never logged or echoed in a response.
 */
public final class CallerGroups {

    private final String groupPrefix;

    public CallerGroups(VersoAuthorityProperties properties) {
        this.groupPrefix = properties.groupPrefix();
    }

    /** The current request's groups, sorted; empty without an authenticated access token. */
    public List<String> current() {
        return of(SecurityContextHolder.getContext().getAuthentication());
    }

    public List<String> of(Authentication authentication) {
        if (!(authentication instanceof JwtAuthenticationToken jwt) || !jwt.isAuthenticated()) return List.of();
        Collection<GrantedAuthority> authorities = jwt.getAuthorities();
        return authorities.stream().map(GrantedAuthority::getAuthority)
                .filter(name -> name != null && name.startsWith(groupPrefix) && name.length() > groupPrefix.length())
                .map(name -> name.substring(groupPrefix.length()))
                .distinct().sorted()
                .limit(ClaimAuthoritiesConverter.MAX_VALUES_PER_CLAIM)
                .toList();
    }
}
