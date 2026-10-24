package com.verso.platform.security.jwt;

import com.verso.platform.security.authority.ClaimAuthoritiesConverter;
import com.verso.platform.security.authority.IssuerAuthoritiesConverter;
import com.verso.platform.security.authority.VersoAuthorityProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * The allowlist of trusted issuers (ADR-0041), built once from configuration. A token is matched to an entry by its
 * exact {@code iss} before anything else happens; an issuer that is not listed here is refused without a key fetch,
 * so a token can never make Verso contact an address it chose.
 */
public final class TrustedIssuers {

    public static final String PRIMARY = "primary";
    public static final String ENTRA = "entra";

    private final List<TrustedIssuer> issuers;

    public TrustedIssuers(List<TrustedIssuer> issuers) {
        if (issuers.isEmpty()) throw new IllegalStateException("no trusted issuer is configured");
        if (issuers.stream().map(TrustedIssuer::issuer).distinct().count() != issuers.size()) {
            throw new IllegalStateException("a trusted issuer is listed twice");
        }
        this.issuers = List.copyOf(issuers);
    }

    public static TrustedIssuers of(VersoJwtProperties jwt, VersoAuthorityProperties authorities) {
        List<TrustedIssuer> issuers = new ArrayList<>();
        if (jwt.primaryEnabled()) {
            issuers.add(new TrustedIssuer(PRIMARY, jwt.issuer(), jwt.jwkSetUri(), Set.copyOf(jwt.algorithms()),
                    jwt.typeHeader(), List.of(jwt.audience()), List.of(), "sub", Map.of(), false, false, authorities));
        }
        VersoJwtProperties.Entra entra = jwt.entra();
        if (entra.enabled()) {
            // Entra ID v2.0 access tokens: RS256 with typ JWT (no RFC 9068 header), one tenant, a delegated token
            // (scp), the tenant-wide object id as the account because sub is pairwise per application.
            issuers.add(new TrustedIssuer(ENTRA, entra.issuer(), entra.keySetUri(), Set.of(SignatureAlgorithm.RS256),
                    "JWT", entra.audiences(), entra.mcpAudiences(), "oid",
                    Map.of("ver", "2.0", "tid", entra.tenantId()), true, true,
                    new VersoAuthorityProperties(entra.rolesClaim(), "", entra.groupsClaim(), authorities.rolePrefix(),
                            authorities.groupPrefix())));
        }
        return new TrustedIssuers(issuers);
    }

    public List<TrustedIssuer> all() {
        return issuers;
    }

    /**
     * Roles and groups with each issuer's own claim paths (ADR-0019); an issuer with a groups overage marker counts
     * every overage in {@link IssuerAuthoritiesConverter#OVERAGE_METRIC}.
     *
     * @param meters null when the application has no meter registry (the overage is then only logged)
     */
    public IssuerAuthoritiesConverter authoritiesConverter(MeterRegistry meters) {
        Map<String, ClaimAuthoritiesConverter> converters = new LinkedHashMap<>();
        for (TrustedIssuer issuer : issuers) {
            Runnable overage = null;
            if (issuer.groupsOverage()) {
                Counter counter = meters == null ? null : Counter.builder(IssuerAuthoritiesConverter.OVERAGE_METRIC)
                        .description("Tokens whose groups the IdP left out (overage); no group was granted")
                        .tag("issuer", issuer.name()).register(meters);
                overage = counter == null ? () -> {} : counter::increment;
            }
            converters.put(issuer.issuer(), new ClaimAuthoritiesConverter(issuer.authorities(), overage));
        }
        return new IssuerAuthoritiesConverter(converters);
    }

    /** The issuers for the MCP resource: the primary one gets {@code verso.mcp.audience}, the others their own. */
    public List<TrustedIssuer> forMcp(String primaryMcpAudience) {
        return issuers.stream()
                .map(i -> PRIMARY.equals(i.name()) ? i.withMcpAudiences(List.of(primaryMcpAudience)) : i)
                .filter(i -> !i.mcpAudiences().isEmpty())
                .toList();
    }
}
