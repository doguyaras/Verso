package com.verso.platform.security.jwt;

import com.nimbusds.jwt.JWT;
import com.nimbusds.jwt.JWTParser;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.util.Map;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/**
 * Picks the decoder of the issuer the token names, before any validation (ADR-0041; the pattern of Spring's
 * {@code JwtIssuerAuthenticationManagerResolver}, kept as a decoder so the API and MCP chains stay as they are).
 *
 * <p>The unverified {@code iss} is only a lookup key into a fixed allowlist: an issuer that is not listed is refused
 * here, without a key fetch, so a token cannot make Verso contact a URL it chose (SSRF) or be checked against keys it
 * brought along. The selected decoder then verifies everything, the issuer included, with that issuer's own rules.
 * Every refusal is a {@link BadJwtException}: a 401, never "IdP unavailable".
 */
final class IssuerSelectingJwtDecoder implements JwtDecoder {

    private final Map<String, JwtDecoder> decoders;

    IssuerSelectingJwtDecoder(Map<String, JwtDecoder> decoders) {
        this.decoders = Map.copyOf(decoders);
    }

    @Override
    public Jwt decode(String token) {
        String issuer;
        try {
            JWT parsed = JWTParser.parse(token);
            // Unsigned (alg none) and encrypted tokens are not access tokens here, whatever their issuer.
            if (!(parsed instanceof SignedJWT)) throw new BadJwtException("The token is not signed");
            issuer = parsed.getJWTClaimsSet().getIssuer();
        } catch (ParseException e) {
            throw new BadJwtException("The token is malformed");
        }
        JwtDecoder decoder = issuer == null ? null : decoders.get(issuer);
        if (decoder == null) throw new BadJwtException("The token's issuer is not trusted");
        return decoder.decode(token);
    }
}
