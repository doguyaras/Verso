package com.verso.platform.core.outbox;

import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The polling relay of one outbox table (reference 11.2, ADR-0039): claim with SKIP LOCKED and a lease, hand each row
 * to the handler of its event type, delete it when delivered, back off when not. Every instance runs it; the claim
 * token keeps a worker whose lease ran out from overwriting the next one's result. The handler's target must be
 * idempotent by the row id (reference 11.2: the claim token does not stop a second delivery).
 *
 * <p>Failures: a transient one (database unreachable, timeout, lock) is retried for ever at the capped backoff and
 * never becomes DEAD, so an outage of the target only delays; anything else becomes DEAD after
 * {@code max-attempts} and is deleted after the failure retention. Logs carry the event id and the exception type,
 * never the payload or an exception message.
 *
 * <p>Metrics, labelled with the outbox name only: {@code outbox_oldest_pending_age_seconds} (reference 14.3: the
 * earliest sign of a stuck relay; it keeps growing when the relay or its database stops, because the age is computed
 * at scrape time from the last oldest row seen), {@code outbox_dead_rows} and {@code outbox_relayed_total{outcome}}.
 */
public final class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    /** Handles one claimed row; returns normally when the target has it (or already had it). */
    @FunctionalInterface
    public interface OutboxHandler {
        void handle(OutboxRow row) throws Exception;
    }

    /** What one poll did. */
    public record BatchResult(int claimed, int delivered, int retried, int dead, int deferred) {
    }

    private final String name;
    private final OutboxStore store;
    private final Map<String, OutboxHandler> handlers;
    private final OutboxProperties properties;
    private final Clock clock;
    /** Oldest undelivered row last seen; null: none. Kept when the database cannot be read, so the age keeps growing. */
    private final AtomicReference<Instant> oldest = new AtomicReference<>();
    private final AtomicLong dead = new AtomicLong();
    /** Nanos until which no eager delivery is tried; JVM-local on purpose (a protective pause per instance). */
    private final AtomicLong eagerPausedUntil = new AtomicLong(System.nanoTime());
    private final Counter delivered;
    private final Counter retried;
    private final Counter deadCounter;
    private final Counter deferred;

    public OutboxRelay(String name, OutboxStore store, Map<String, OutboxHandler> handlers,
                       OutboxProperties properties, Clock clock, MeterRegistry registry) {
        this.name = Objects.requireNonNull(name, "name");
        this.store = Objects.requireNonNull(store, "store");
        this.handlers = Map.copyOf(handlers);
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        Gauge.builder("outbox.oldest.pending.age", this, OutboxRelay::oldestPendingAgeSeconds).baseUnit("seconds")
                .tag("outbox", name).description("Age of the oldest outbox row not yet delivered").register(registry);
        Gauge.builder("outbox.dead.rows", dead, AtomicLong::get).tag("outbox", name)
                .description("Outbox rows that gave up, kept for the failure retention").register(registry);
        this.delivered = counter(registry, "delivered");
        this.retried = counter(registry, "retried");
        this.deadCounter = counter(registry, "dead");
        this.deferred = counter(registry, "deferred");
    }

    /** One poll: claim a batch and handle it. Throws only when the claim itself fails (the caller logs and polls again). */
    public BatchResult runOnce() {
        long started = System.nanoTime();
        UUID token = UUID.randomUUID();
        List<OutboxRow> rows = store.claimPending(properties.batchSize(), properties.lease(), token);
        long budgetNanos = properties.lease().minus(properties.leaseMargin()).toNanos();
        int ok = 0;
        int failed = 0;
        int gaveUp = 0;
        int handedBack = 0;
        for (OutboxRow row : rows) {
            if (System.nanoTime() - started >= budgetNanos) {
                if (store.release(row.id(), row.claimToken())) handedBack++;
                deferred.increment();
                continue;
            }
            switch (deliver(row)) {
                case DELIVERED -> ok++;
                case RETRY -> failed++;
                case DEAD -> gaveUp++;
            }
        }
        refreshGauges();
        if (!rows.isEmpty()) {
            log.info("Outbox batch finished: outbox={} claimed={} delivered={} retried={} dead={} deferred={}",
                    name, rows.size(), ok, failed, gaveUp, handedBack);
        }
        return new BatchResult(rows.size(), ok, failed, gaveUp, handedBack);
    }

    /**
     * Eager delivery of a row the current transaction wrote (verso.outbox.eager-delivery): once the transaction has
     * committed, the row is delivered before this thread goes on, so the receiver has it when the response is sent.
     * Never throws and never delays a rollback; anything it misses stays PENDING for {@link #runOnce()}.
     *
     * <p>The delivery runs on a short-lived virtual thread that the request waits for (at most eager-timeout): inside
     * afterCompletion the finished transaction's connection is still bound to the request thread, and statements
     * there would join it instead of committing on their own.
     */
    public void deliverAfterCommit(UUID id) {
        if (!properties.eagerDelivery() || !TransactionSynchronizationManager.isSynchronizationActive()) return;
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED || System.nanoTime() - eagerPausedUntil.get() < 0) return;
                Thread worker = Thread.ofVirtual().name("outbox-eager-" + name).start(() -> deliverNow(id));
                try {
                    worker.join(properties.eagerTimeout());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
    }

    /** Claims and delivers one row by id; false when it was not delivered now (taken, gone, failed or paused). */
    public boolean deliverNow(UUID id) {
        try {
            if (System.nanoTime() - eagerPausedUntil.get() < 0) return false;
            Optional<OutboxRow> row = store.claimById(id, properties.lease(), UUID.randomUUID());
            if (row.isEmpty()) return false;
            if (deliver(row.get()) == Outcome.DELIVERED) return true;
        } catch (RuntimeException e) {
            log.warn("Outbox eager delivery failed: outbox={} eventId={} exceptionType={}", name, id,
                    e.getClass().getSimpleName());
        }
        eagerPausedUntil.set(System.nanoTime() + properties.eagerPause().toNanos());
        return false;
    }

    /** The failure retention: DEAD rows older than {@code dead-retention}, in short batches. */
    public int purgeDead() {
        int total = 0;
        int batch;
        do {
            batch = store.purgeDead(properties.deadRetention(), properties.batchSize() * 10);
            total += batch;
        } while (batch > 0 && total < 100_000);
        if (total > 0) log.info("Outbox dead rows purged: outbox={} deleted={}", name, total);
        refreshGauges();
        return total;
    }

    /** Reads the oldest undelivered row and the DEAD count; on a failure the last values stay (age keeps growing). */
    public void refreshGauges() {
        try {
            oldest.set(store.oldestPending().orElse(null));
            dead.set(store.countDead());
        } catch (RuntimeException e) {
            log.warn("Outbox gauges not refreshed: outbox={} exceptionType={}", name, e.getClass().getSimpleName());
        }
    }

    double oldestPendingAgeSeconds() {
        Instant first = oldest.get();
        if (first == null) return 0;
        return Math.max(0, Duration.between(first, clock.instant()).toMillis() / 1000.0);
    }

    private enum Outcome { DELIVERED, RETRY, DEAD }

    private Outcome deliver(OutboxRow row) {
        OutboxHandler handler = handlers.get(row.eventType());
        try {
            if (handler == null) throw new IllegalStateException("no handler");
            handler.handle(row);
            store.deleteProcessed(row.id(), row.claimToken());
            delivered.increment();
            return Outcome.DELIVERED;
        } catch (Exception e) {
            String type = e.getClass().getSimpleName();
            int attempt = row.retryCount() + 1;
            boolean transientFailure = isTransient(e);
            try {
                if (!transientFailure && attempt >= properties.maxAttempts()) {
                    store.markDead(row.id(), row.claimToken(), type);
                    deadCounter.increment();
                    log.error("Outbox row gave up: code=OUTBOX_DEAD outbox={} eventId={} retry={} exceptionType={}",
                            name, row.id(), attempt, type);
                    return Outcome.DEAD;
                }
                store.markFailed(row.id(), row.claimToken(), type, properties.backoffFor(row.retryCount()));
            } catch (RuntimeException markFailure) {
                // The row stays claimed; the lease returns it to the pool.
                log.warn("Outbox row not marked: outbox={} eventId={} exceptionType={}", name, row.id(),
                        markFailure.getClass().getSimpleName());
            }
            retried.increment();
            if (attempt % properties.stuckEvery() == 0) {
                log.error("Outbox row still failing: code=OUTBOX_STUCK outbox={} eventId={} retry={} exceptionType={}",
                        name, row.id(), attempt, type);
            } else {
                log.warn("Outbox row failed: outbox={} eventId={} retry={} transient={} exceptionType={}", name,
                        row.id(), attempt, transientFailure, type);
            }
            return Outcome.RETRY;
        }
    }

    /** The target or its database is away for a while: retried for ever, never DEAD. */
    static boolean isTransient(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof TransientDataAccessException || t instanceof RecoverableDataAccessException
                    || t instanceof DataAccessResourceFailureException || t instanceof TransactionException
                    || t instanceof java.sql.SQLTransientException || t instanceof java.net.ConnectException) {
                return true;
            }
        }
        return false;
    }

    private Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("outbox.relayed").tag("outbox", name).tag("outcome", outcome)
                .description("Outbox rows handled by the relay").register(registry);
    }
}
