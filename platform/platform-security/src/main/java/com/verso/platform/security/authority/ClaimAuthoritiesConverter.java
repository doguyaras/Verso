package com.verso.platform.security.authority;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * The authorities of a validated access token (ADR-0019), from claim paths set in {@link VersoAuthorityProperties}:
 *
 * <ul>
 *   <li>the scopes, as before ({@code SCOPE_*});</li>
 *   <li>{@code <role-prefix>verso-user} for every token: a caller without any Verso role is a regular user;</li>
 *   <li>{@code <role-prefix><role>} for each value of the roles claim and of the optional client roles claim;</li>
 *   <li>{@code <group-prefix><group>} for each value of the optional groups claim.</li>
 * </ul>
 *
 * <p>Fail closed: a missing claim grants nothing, and a malformed one (not a list of strings, a value that is blank,
 * too long or carries control characters) is skipped rather than rejecting the token, so an IdP quirk costs the extra
 * rights, never the sign-in. The claim values are the caller's data and are never logged; a malformed claim is logged
 * once per claim and reason, with the claim's kind only.
 */
public final class ClaimAuthoritiesConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final Logger log = LoggerFactory.getLogger(ClaimAuthoritiesConverter.class);
    /** Entra ID puts at most 200 groups into a JWT (more is an overage claim); this bounds the work per request. */
    static final int MAX_VALUES_PER_CLAIM = 256;
    static final int MAX_VALUE_LENGTH = 256;

    private final VersoAuthorityProperties properties;
    private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
    /**
     * Log de-duplication only, per instance: a misconfigured claim path would otherwise log on every request. Not
     * shared state between instances; each one logs its own first occurrence.
     */
    private final Set<String> reported = ConcurrentHashMap.newKeySet();

    /** Called on an Entra groups overage; null when the issuer has no such marker. */
    private final Runnable groupsOverage;

    public ClaimAuthoritiesConverter(VersoAuthorityProperties properties) {
        this(properties, null);
    }

    /**
     * @param groupsOverage when not null, the token is checked for Entra ID's groups overage (more than 200 groups:
     *                      the groups claim is replaced by a {@code _claim_names} pointer to Microsoft Graph). Verso
     *                      does not call Graph (ADR-0041); the caller then has no group at all, never a partial list,
     *                      and this callback counts it.
     */
    public ClaimAuthoritiesConverter(VersoAuthorityProperties properties, Runnable groupsOverage) {
        this.properties = properties;
        this.groupsOverage = groupsOverage;
    }

    @Override
    public Collection<GrantedAuthority> convert(Jwt jwt) {
        Set<GrantedAuthority> authorities = new LinkedHashSet<>(scopes.convert(jwt));
        authorities.add(new SimpleGrantedAuthority(properties.rolePrefix() + VersoRoles.USER));
        add(authorities, properties.rolePrefix(), values(jwt, properties.rolesClaim(), "roles"));
        add(authorities, properties.rolePrefix(), values(jwt, properties.clientRolesClaim(), "client-roles"));
        if (groupsOverage != null && hasGroupsOverage(jwt)) {
            // Fail closed: a list the IdP marks as incomplete grants nothing, even if a groups claim is also present.
            // Logged once per instance (every request of such a user carries it); the counter has every occurrence.
            if (reported.add("groups:OVERAGE")) {
                log.warn("Authority claim rejected: code=GROUPS_OVERAGE reason=GROUPS_NOT_IN_TOKEN claim=groups");
            }
            groupsOverage.run();
        } else {
            add(authorities, properties.groupPrefix(), values(jwt, properties.groupsClaim(), "groups"));
        }
        return List.copyOf(authorities);
    }

    /** Entra's overage markers: {@code _claim_names.groups} (JWT access tokens) or {@code hasgroups: true}. */
    private static boolean hasGroupsOverage(Jwt jwt) {
        return jwt.getClaims().get("_claim_names") instanceof Map<?, ?> names && names.containsKey("groups")
                || Boolean.TRUE.equals(jwt.getClaims().get("hasgroups"))
                || "true".equals(jwt.getClaims().get("hasgroups"));
    }

    private static void add(Set<GrantedAuthority> authorities, String prefix, List<String> values) {
        for (String value : values) authorities.add(new SimpleGrantedAuthority(prefix + value));
    }

    /** The string values at the path; empty when the path is unset, absent or not a list of strings. */
    List<String> values(Jwt jwt, String path, String kind) {
        if (path.isEmpty()) return List.of();
        Object node = jwt.getClaims();
        for (String segment : path.split("\\.")) {
            if (node == null) return List.of();
            if (!(node instanceof Map<?, ?> object)) {
                report(kind, "NOT_AN_OBJECT");
                return List.of();
            }
            node = object.get(segment);
        }
        if (node == null) return List.of();
        // A single value: some IdPs (AD FS among them) send one role as a string rather than a one-element list.
        Collection<?> raw = node instanceof String single ? List.of(single) : node instanceof Collection<?> list ? list : null;
        if (raw == null) {
            report(kind, "NOT_A_LIST");
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (Object value : raw) {
            if (values.size() == MAX_VALUES_PER_CLAIM) {
                report(kind, "TOO_MANY_VALUES");
                break;
            }
            if (value instanceof String text && acceptable(text)) {
                values.add(text);
            } else {
                report(kind, "INVALID_VALUE");
            }
        }
        return values;
    }

    /** Printable and bounded: a value becomes an authority and, later, an access-control principal. */
    private static boolean acceptable(String value) {
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) return false;
        return value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }

    private void report(String kind, String reason) {
        if (reported.add(kind + ':' + reason)) {
            log.warn("Authority claim rejected: code=MALFORMED_CLAIM reason={} claim={}", reason, kind);
        }
    }
}
