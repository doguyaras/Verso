package com.verso.document.api.enums;

/**
 * Whom a collection is granted to (ADR-0028). There is no "everyone", no role and no deny: a grant only adds readers,
 * and an empty grant list means nobody reads the collection through Verso (fail closed).
 */
public enum PrincipalType {
    /** An IdP group, by the exact value of the token's groups claim (Keycloak: the full path; Entra: the object id). */
    GROUP,
    /** One user, by the token's sub. */
    USER
}
