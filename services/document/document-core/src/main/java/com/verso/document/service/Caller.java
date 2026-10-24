package com.verso.document.service;

import com.verso.document.api.dto.Reader;
import com.verso.platform.security.web.AccountId;
import java.util.Objects;

/**
 * The validated caller of a document or collection use case (ADR-0028): the account (token sub), the principals it
 * reads with (sub and IdP groups) and whether the token carries {@code verso-admin}. Built by the controller from the
 * security context, never from the request. {@code admin} decides management (collections, collection documents), never
 * reading: what an admin reads is still decided by {@link #reader()} (least privilege).
 */
public record Caller(AccountId account, Reader reader, boolean admin) {

    public Caller {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(reader, "reader");
        if (!reader.accountId().equals(account.value())) throw new IllegalArgumentException("reader must be the account");
    }

    public String[] principals() {
        return reader.principals().toArray(String[]::new);
    }

    @Override
    public String toString() {
        return "Caller[admin=" + admin + ", " + reader + "]";
    }
}
