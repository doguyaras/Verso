package com.verso.connector.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The CQL of a Confluence listing (ADR-0044), a pure function so quoting is unit-tested. Every value is a quoted CQL
 * string with backslash and double quote escaped, after the API already restricted keys and labels to safe characters
 * (two fences: an admin value can never close the string and add a clause such as {@code OR space = "HR"}).
 *
 * <p>The incremental window is relative to the server's clock, {@code lastmodified >= now("-125m")}: an absolute date in
 * CQL is read in the API user's time zone, which the connector does not know, while {@code now()} is the server's own
 * instant. The window reaches back to the cursor plus an overlap; pages seen twice are recognised by their version and
 * cost nothing but a list entry.
 */
public final class CqlBuilder {

    private static final Pattern SPACE_KEY = Pattern.compile("[A-Za-z0-9_~.-]{1,100}");
    private static final Pattern LABEL = Pattern.compile("[\\p{L}\\p{N}_.:-]{1,255}");

    private CqlBuilder() {}

    /**
     * @param sinceMinutes null for a full listing, else only pages modified in the last that many minutes
     * @throws IllegalArgumentException when a key or label is not of the API's shape (a stored row from an older rule)
     */
    public static String pages(List<String> spaceKeys, String label, Long ancestorId, Long sinceMinutes) {
        if (spaceKeys == null || spaceKeys.isEmpty() || spaceKeys.size() > 20) {
            throw new IllegalArgumentException("SPACE_KEYS_INVALID");
        }
        StringBuilder cql = new StringBuilder("type = page AND space in (");
        for (int i = 0; i < spaceKeys.size(); i++) {
            String key = spaceKeys.get(i);
            if (key == null || !SPACE_KEY.matcher(key).matches()) throw new IllegalArgumentException("SPACE_KEYS_INVALID");
            if (i > 0) cql.append(',');
            cql.append(quote(key));
        }
        cql.append(')');
        if (label != null) {
            if (!LABEL.matcher(label).matches()) throw new IllegalArgumentException("LABEL_INVALID");
            cql.append(" AND label = ").append(quote(label));
        }
        if (ancestorId != null) {
            if (ancestorId <= 0) throw new IllegalArgumentException("ANCESTOR_INVALID");
            cql.append(" AND ancestor = ").append(ancestorId);
        }
        if (sinceMinutes != null) {
            if (sinceMinutes < 1) throw new IllegalArgumentException("WINDOW_INVALID");
            cql.append(" AND lastmodified >= now(\"-").append(sinceMinutes).append("m\")");
        }
        // Creation order is stable while pages are edited during the walk: an edited page does not move.
        return cql.append(" ORDER BY created ASC").toString();
    }

    /** A CQL string literal. */
    public static String quote(String value) {
        StringBuilder out = new StringBuilder(value.length() + 2).append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '\\' || c == '"') out.append('\\');
            if (c < 0x20 || c == 0x7f) continue;
            out.append(c);
        }
        return out.append('"').toString();
    }

    /** Minutes from the cursor back over the overlap to now, rounded up; at least 1. */
    public static long windowMinutes(Instant cursor, Instant now, Duration overlap) {
        Duration window = Duration.between(cursor, now).plus(overlap);
        if (window.isNegative() || window.isZero()) return 1;
        long minutes = window.toMinutes();
        if (window.minusMinutes(minutes).isPositive()) minutes++;
        return Math.max(1, minutes);
    }
}
