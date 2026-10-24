package com.verso.platform.security.authority;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtClaimNames;

/**
 * The authorities of a validated token, read with its own issuer's claim paths (ADR-0019, ADR-0041): Keycloak's
 * {@code realm_access.roles} is never read from an Entra token, nor Entra's {@code roles} from a Keycloak token. The
 * issuer was checked by the decoder; a token of an issuer without an entry here gets no authority at all.
 */
public final class IssuerAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    /** Entra groups overage occurrences (ADR-0041): the users concerned have no group-based access. */
    public static final String OVERAGE_METRIC = "verso.security.groups.overage";

    private final Map<String, ClaimAuthoritiesConverter> converters;

    /** @param converters per exact issuer */
    public IssuerAuthoritiesConverter(Map<String, ClaimAuthoritiesConverter> converters) {
        this.converters = Map.copyOf(converters);
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        String issuer = jwt.getClaimAsString(JwtClaimNames.ISS);
        ClaimAuthoritiesConverter converter = issuer == null ? null : converters.get(issuer);
        return converter == null ? List.of() : converter.convert(jwt);
    }
}
