package com.verso.document.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Who reads (ADR-0028): the validated token's sub and its IdP groups. Every read of documents, chunks and citations is
 * filtered by {@link #principals()} inside the query (llm-rules 4.1): {@code user:<sub>} reaches the caller's personal
 * documents and the collections granted to that sub, {@code group:<value>} the collections granted to that group.
 *
 * <p>Fail closed: a group value that could not be a stored principal (blank, longer than {@value #MAX_VALUE_LENGTH},
 * control characters) is dropped, and at most {@value #MAX_GROUPS} groups count; no groups means personal documents
 * and direct user grants only. The groups are the customer's directory data: {@link #toString()} never prints them.
 *
 * @param accountId the validated token's sub (never taken from a request)
 * @param groups    the token's group claim values (ADR-0019), already validated by the platform's converter
 */
public record Reader(String accountId, List<String> groups) {

    public static final int MAX_GROUPS = 256;
    public static final int MAX_VALUE_LENGTH = 256;
    public static final String USER_PREFIX = "user:";
    public static final String GROUP_PREFIX = "group:";

    public Reader {
        if (accountId == null || !acceptable(accountId)) throw new IllegalArgumentException("account id is required");
        groups = groups == null ? List.of()
                : groups.stream().filter(Objects::nonNull).filter(Reader::acceptable).distinct().sorted()
                .limit(MAX_GROUPS).toList();
    }

    /** A reader without groups: personal documents and collections granted to this sub only. */
    public static Reader personal(String accountId) {
        return new Reader(accountId, List.of());
    }

    /** The principal of the caller's personal space; also how a collection is granted to one user. */
    public String userPrincipal() {
        return USER_PREFIX + accountId;
    }

    /** {@code user:<sub>} first, then {@code group:<value>} per group: what document_principal rows are matched against. */
    public List<String> principals() {
        List<String> all = new ArrayList<>(groups.size() + 1);
        all.add(userPrincipal());
        for (String group : groups) all.add(GROUP_PREFIX + group);
        return List.copyOf(all);
    }

    /** Printable and bounded, like the stored principal (V4 CHECK) and the platform's claim rule (ADR-0019). */
    private static boolean acceptable(String value) {
        if (value.isBlank() || value.length() > MAX_VALUE_LENGTH) return false;
        return value.codePoints().noneMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT);
    }

    @Override
    public String toString() {
        return "Reader[groups=" + groups.size() + "]";
    }
}
