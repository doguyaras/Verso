package com.verso.platform.security.web;

/**
 * The caller's account: the {@code sub} claim of a validated access token (JwtValidation checks its shape). A value
 * object, so an account id cannot be passed where a document id or a free string is expected.
 */
public record AccountId(String value) {

    public AccountId {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("account id is required");
    }

    @Override
    public String toString() {
        return value;
    }
}
