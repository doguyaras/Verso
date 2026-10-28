package com.verso.platform.core.activity;

import java.util.List;

/**
 * Who looks at the activity centre: the validated token's sub and its IdP groups (ADR-0028), so that a module can tell
 * which names the viewer may read. It is used for access checks only; no entry ever carries it.
 */
public record ActivityViewer(String accountId, List<String> groups) {

    public ActivityViewer {
        if (accountId == null || accountId.isBlank()) throw new IllegalArgumentException("account id is required");
        groups = groups == null ? List.of() : List.copyOf(groups);
    }

    @Override
    public String toString() {
        return "ActivityViewer[groups=" + groups.size() + "]";
    }
}
