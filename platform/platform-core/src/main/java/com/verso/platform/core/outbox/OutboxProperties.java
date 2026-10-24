package com.verso.platform.core.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The outbox relay's starting values (reference 11.2 "Poller sabitleri", ADR-0039). One set for the whole application:
 * Verso is one service (shape A) with one outbox table per publishing module schema.
 *
 * @param enabled        the scheduled relay (tests drive it themselves); the writer always writes
 * @param pollInterval   pause between two polls of one instance; every instance polls (SKIP LOCKED)
 * @param batchSize      rows claimed per poll
 * @param lease          a claimed row returns to the pool after this, if its worker died
 * @param leaseMargin    no row is started once less than this is left of the lease; the rest is handed back
 * @param backoff        first retry delay; doubled per attempt
 * @param maxBackoff     ceiling of the retry delay; a transient failure is retried at this pace for ever
 * @param maxAttempts    a failure that is not transient (a payload the receiver refuses) becomes DEAD after this many
 * @param stuckEvery     every this many attempts of one row an ERROR line {@code code=OUTBOX_STUCK}
 * @param deadRetention  DEAD rows are deleted after this (the failure retention); the alert fires long before
 * @param purgeInterval  how often the failure retention runs
 * @param eagerDelivery  right after the commit, the writing thread delivers its own rows (bounded by eager-timeout),
 *                       so the audit row exists when the response is sent, as before ADR-0039; the poll stays the
 *                       safety net for anything this misses (crash, timeout, outage)
 * @param eagerTimeout   longest wait of a request for its eager delivery; the delivery itself may finish later
 * @param eagerPause     after a failed eager delivery no request tries again for this long (an outage costs one
 *                       wait, not one per request); the poll keeps retrying with its backoff
 */
@ConfigurationProperties("verso.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("1s") Duration pollInterval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("120s") Duration lease,
        @DefaultValue("30s") Duration leaseMargin,
        @DefaultValue("30s") Duration backoff,
        @DefaultValue("600s") Duration maxBackoff,
        @DefaultValue("20") int maxAttempts,
        @DefaultValue("10") int stuckEvery,
        @DefaultValue("30d") Duration deadRetention,
        @DefaultValue("1h") Duration purgeInterval,
        @DefaultValue("true") boolean eagerDelivery,
        @DefaultValue("3s") Duration eagerTimeout,
        @DefaultValue("10s") Duration eagerPause) {

    public OutboxProperties {
        if (pollInterval.compareTo(Duration.ofMillis(100)) < 0 || pollInterval.compareTo(Duration.ofMinutes(1)) > 0) {
            throw new IllegalStateException("verso.outbox.poll-interval must be 100ms..1m");
        }
        if (batchSize < 1 || batchSize > 500) throw new IllegalStateException("verso.outbox.batch-size must be 1..500");
        if (lease.compareTo(Duration.ofSeconds(10)) < 0) throw new IllegalStateException("verso.outbox.lease must be >= 10s");
        if (leaseMargin.isNegative() || leaseMargin.compareTo(lease) >= 0) {
            throw new IllegalStateException("verso.outbox.lease-margin must be 0..lease");
        }
        if (!backoff.isPositive() || maxBackoff.compareTo(backoff) < 0) {
            throw new IllegalStateException("verso.outbox.backoff must be positive and <= max-backoff");
        }
        if (maxAttempts < 1) throw new IllegalStateException("verso.outbox.max-attempts must be >= 1");
        if (stuckEvery < 1) throw new IllegalStateException("verso.outbox.stuck-every must be >= 1");
        if (deadRetention.compareTo(Duration.ofDays(1)) < 0) {
            throw new IllegalStateException("verso.outbox.dead-retention must be >= 1d");
        }
        if (!purgeInterval.isPositive()) throw new IllegalStateException("verso.outbox.purge-interval must be positive");
        if (!eagerTimeout.isPositive() || eagerTimeout.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("verso.outbox.eager-timeout must be 1ms..10s");
        }
        if (eagerPause.isNegative()) throw new IllegalStateException("verso.outbox.eager-pause must not be negative");
    }

    /** {@code min(maxBackoff, backoff * 2^attempt)}; attempt 0 is the first retry. */
    public Duration backoffFor(int attempt) {
        long factor = 1L << Math.min(Math.max(attempt, 0), 20);
        Duration next = backoff.multipliedBy(factor);
        return next.compareTo(maxBackoff) > 0 ? maxBackoff : next;
    }
}
