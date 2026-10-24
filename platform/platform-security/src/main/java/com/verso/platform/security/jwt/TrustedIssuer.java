package com.verso.platform.security.jwt;

import com.verso.platform.security.authority.VersoAuthorityProperties;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;

/**
 * One issuer Verso trusts, with everything its tokens are checked against (ADR-0041). Nothing here is global: the
 * algorithms, the {@code typ} header, the audiences and the claim shape belong to the issuer, and a token is only ever
 * checked against the entry its {@code iss} names exactly.
 *
 * @param name           a short, fixed label for metrics and logs ({@code primary}, {@code entra}); never the URL
 * @param issuer         exact {@code iss}
 * @param jwkSetUri      where this issuer's keys come from; fetched only for a token that names this issuer
 * @param algorithms     the only signature algorithms accepted; the key type must match (EC for ES256, RSA for RS256)
 * @param typeHeader     required JOSE {@code typ}; empty: not checked
 * @param audiences      the API audiences; {@code aud} must contain one
 * @param mcpAudiences   the MCP audiences (ADR-0031); empty: this issuer's tokens are not accepted on /mcp
 * @param accountClaim   the claim that becomes the account id ({@code sub}); Entra: {@code oid}
 * @param requiredClaims claims that must be present with exactly this string value (Entra: {@code ver}, {@code tid})
 * @param requireScope   a non-empty {@code scp} is required: a delegated access token, never an ID token
 * @param groupsOverage  the issuer may replace the groups claim by an overage pointer (Entra, more than 200 groups);
 *                       then no group is granted
 * @param authorities    where the roles and groups are in this issuer's tokens (ADR-0019)
 */
public record TrustedIssuer(
        String name,
        String issuer,
        URI jwkSetUri,
        Set<SignatureAlgorithm> algorithms,
        String typeHeader,
        List<String> audiences,
        List<String> mcpAudiences,
        String accountClaim,
        Map<String, String> requiredClaims,
        boolean requireScope,
        boolean groupsOverage,
        VersoAuthorityProperties authorities) {

    public TrustedIssuer {
        algorithms = Set.copyOf(algorithms);
        audiences = List.copyOf(audiences);
        mcpAudiences = List.copyOf(mcpAudiences);
        requiredClaims = Map.copyOf(requiredClaims);
        if (algorithms.isEmpty()) throw new IllegalStateException("issuer " + name + " has no algorithm");
    }

    /** The same issuer with the MCP audiences the MCP module configures for it. */
    public TrustedIssuer withMcpAudiences(List<String> audiences) {
        return new TrustedIssuer(name, issuer, jwkSetUri, algorithms, typeHeader, this.audiences, audiences,
                accountClaim, requiredClaims, requireScope, groupsOverage, authorities);
    }
}
