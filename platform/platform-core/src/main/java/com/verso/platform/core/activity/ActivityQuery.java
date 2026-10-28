package com.verso.platform.core.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * The window a source answers for. {@code from} inclusive, {@code to} exclusive; {@code collection} narrows the
 * entries that belong to a shared collection (entries without one are left out when it is set); {@code limit} bounds
 * every source's answer, so the merge never holds more than sources x limit entries.
 */
public record ActivityQuery(Instant from, Instant to, UUID collection, int limit, ActivityViewer viewer) {

    /** The most entries one source may return for one request. */
    public static final int MAX_LIMIT = 500;

    public ActivityQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(viewer, "viewer");
        if (!from.isBefore(to)) throw new IllegalArgumentException("from must be before to");
        if (limit < 1 || limit > MAX_LIMIT) throw new IllegalArgumentException("limit out of range");
    }

    /** Whether an entry of {@code entryCollection} passes the collection filter. */
    public boolean admits(UUID entryCollection) {
        return collection == null || collection.equals(entryCollection);
    }

    public boolean contains(Instant at) {
        return at != null && !at.isBefore(from) && at.isBefore(to);
    }
}
