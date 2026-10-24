package com.verso.connector.domain;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.function.DoubleSupplier;

/**
 * How long to wait before repeating a Confluence request that was throttled (429), unavailable (503) or cut off
 * (ADR-0044); a pure function so the rule is unit-tested. The server's Retry-After wins (seconds or an HTTP date);
 * without one, exponential backoff with jitter so several instances do not retry in step. A wait longer than the cap is
 * not slept: the run stops and the connector backs off as a whole, instead of holding the worker for minutes.
 */
public final class RetryPolicy {

    private RetryPolicy() {}

    /**
     * @param attempt    the attempt that just failed, from 1
     * @param retryAfter the Retry-After header or null
     * @param random     0..1 (jitter); tests pass a constant
     * @return the wait, or null when the server asks for longer than {@code max}
     */
    public static Duration delay(int attempt, String retryAfter, Instant now, Duration base, Duration max,
                                 DoubleSupplier random) {
        Duration asked = parseRetryAfter(retryAfter, now);
        if (asked != null) return asked.compareTo(max) > 0 ? null : asked;
        int shift = Math.max(0, Math.min(attempt - 1, 20));
        Duration exponential = base.multipliedBy(1L << shift);
        if (exponential.compareTo(max) > 0) exponential = max;
        // "Equal jitter": at least half of the backoff, so a retry never comes immediately.
        long half = exponential.toMillis() / 2;
        double r = Math.max(0, Math.min(1, random.getAsDouble()));
        return Duration.ofMillis(half + (long) (r * (exponential.toMillis() - half)));
    }

    /** Retry-After as delta-seconds or an IMF-fixdate; null when absent or malformed (then the backoff applies). */
    static Duration parseRetryAfter(String value, Instant now) {
        if (value == null || value.isBlank()) return null;
        String v = value.strip();
        if (v.chars().allMatch(Character::isDigit)) {
            if (v.length() > 9) return Duration.ofDays(365);
            return Duration.ofSeconds(Long.parseLong(v));
        }
        try {
            Instant at = ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration wait = Duration.between(now, at);
            return wait.isNegative() ? Duration.ZERO : wait;
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
