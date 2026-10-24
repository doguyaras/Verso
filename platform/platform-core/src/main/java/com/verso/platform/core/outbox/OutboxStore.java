package com.verso.platform.core.outbox;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * SQL of one module's generic outbox table, {@code <schema>.outbox_event} (reference 11.2, ADR-0039), on that module's
 * own connection and role. The table lives in the publishing module's schema, so the row commits or rolls back with
 * the business change; nothing here opens a transaction.
 *
 * <p>Statement names follow reference 5.6: {@link #append}, {@link #claimPending}, {@link #deleteProcessed},
 * {@link #markFailed}, {@link #markDead}, {@link #release}, {@link #purgeDead}.
 */
public final class OutboxStore {

    private static final Pattern TABLE = Pattern.compile("[a-z][a-z0-9_]{0,30}\\.outbox_event");
    private static final Pattern WORD = Pattern.compile("[a-z][a-z0-9_.-]{0,99}");
    private static final Pattern ERROR_CODE = Pattern.compile("[A-Za-z0-9_.$]{1,120}");

    /** A claimed row: everything the handler needs, nothing it could change. */
    public record OutboxRow(UUID id, String eventType, String payload, int retryCount, Instant createdAt,
                            UUID claimToken) {
    }

    private final String table;
    private final JdbcClient jdbc;
    private final BooleanSupplier inTransaction;

    /**
     * @param table         {@code <schema>.outbox_event}; a fixed name from code, checked, never input
     * @param jdbc          the module's own JdbcClient (its pool, its role)
     * @param inTransaction true while a transaction of exactly this connection is open on the current thread; the
     *                      writer is MANDATORY (reference 4.4) and refuses to write outside one
     */
    public OutboxStore(String table, JdbcClient jdbc, BooleanSupplier inTransaction) {
        if (table == null || !TABLE.matcher(table).matches()) {
            throw new IllegalArgumentException("outbox table must be <schema>.outbox_event");
        }
        this.table = table;
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
        this.inTransaction = Objects.requireNonNull(inTransaction, "inTransaction");
    }

    public String table() {
        return table;
    }

    /**
     * Writes one PENDING row in the caller's transaction (Propagation.MANDATORY semantics). The id is the event id:
     * the receiving side deduplicates by it, so a retried delivery has one effect.
     */
    public void append(UUID id, String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        if (!inTransaction.getAsBoolean()) {
            throw new IllegalStateException("outbox rows are written only inside the module's own transaction");
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(aggregateId, "aggregateId");
        requireWord(aggregateType, "aggregateType");
        requireWord(eventType, "eventType");
        Objects.requireNonNull(payloadJson, "payloadJson");
        jdbc.sql("INSERT INTO " + table + " (id, kind, aggregate_type, aggregate_id, event_type, payload)"
                        + " VALUES (:id, 'EVENT', :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))")
                .param("id", id).param("aggregateType", aggregateType).param("aggregateId", aggregateId)
                .param("eventType", eventType).param("payload", payloadJson)
                .update();
    }

    /**
     * Claims at most {@code batch} due rows for {@code lease}: PENDING rows whose retry time has come, and PUBLISHING
     * rows whose lease ran out (their worker died). One statement, autocommit: FOR UPDATE SKIP LOCKED lets every
     * instance poll at once without two of them taking the same row (reference 11.1).
     */
    public List<OutboxRow> claimPending(int batch, Duration lease, UUID claimToken) {
        return jdbc.sql("WITH due AS (SELECT id FROM " + table
                        + " WHERE kind = 'EVENT' AND ((status = 'PENDING' AND next_retry_at <= now())"
                        + " OR (status = 'PUBLISHING' AND locked_until < now()))"
                        + " ORDER BY priority DESC, created_at LIMIT :batch FOR UPDATE SKIP LOCKED)"
                        + " UPDATE " + table + " o SET status = 'PUBLISHING', claim_token = :token,"
                        + " locked_until = now() + make_interval(secs => :leaseSeconds)"
                        + " FROM due WHERE o.id = due.id"
                        + " RETURNING o.id, o.event_type, o.payload::text AS payload, o.retry_count, o.created_at,"
                        + " o.claim_token")
                .param("batch", batch).param("token", claimToken).param("leaseSeconds", (double) lease.toSeconds())
                .query(OutboxStore::row).list();
    }

    /**
     * Claims one PENDING row by id for the eager delivery right after its commit; empty when another worker holds it
     * (SKIP LOCKED: never waits for the poller) or it is gone already.
     */
    public Optional<OutboxRow> claimById(UUID id, Duration lease, UUID claimToken) {
        return jdbc.sql("UPDATE " + table + " o SET status = 'PUBLISHING', claim_token = :token,"
                        + " locked_until = now() + make_interval(secs => :leaseSeconds)"
                        + " WHERE o.id = (SELECT id FROM " + table + " WHERE id = :id AND status = 'PENDING'"
                        + " FOR UPDATE SKIP LOCKED)"
                        + " RETURNING o.id, o.event_type, o.payload::text AS payload, o.retry_count, o.created_at,"
                        + " o.claim_token")
                .param("id", id).param("token", claimToken).param("leaseSeconds", (double) lease.toSeconds())
                .query(OutboxStore::row).optional();
    }

    /** Delivered: the row goes (reference 11.2: outbox rows are deleted, not archived). False if the lease was lost. */
    public boolean deleteProcessed(UUID id, UUID claimToken) {
        return jdbc.sql("DELETE FROM " + table + " WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", claimToken).update() == 1;
    }

    /** Failed this time: back to PENDING after {@code backoff}, one more attempt counted, the exception type kept. */
    public boolean markFailed(UUID id, UUID claimToken, String errorCode, Duration backoff) {
        return jdbc.sql("UPDATE " + table + " SET status = 'PENDING', retry_count = retry_count + 1,"
                        + " next_retry_at = now() + make_interval(secs => :backoffSeconds), locked_until = NULL,"
                        + " claim_token = NULL, last_error_code = :error WHERE id = :id AND claim_token = :token")
                .param("backoffSeconds", (double) backoff.toSeconds()).param("error", errorCode(errorCode))
                .param("id", id).param("token", claimToken).update() == 1;
    }

    /** Will not succeed: kept as DEAD for the failure retention, out of the claim and age queries. */
    public boolean markDead(UUID id, UUID claimToken, String errorCode) {
        return jdbc.sql("UPDATE " + table + " SET status = 'DEAD', retry_count = retry_count + 1, locked_until = NULL,"
                        + " claim_token = NULL, last_error_code = :error WHERE id = :id AND claim_token = :token")
                .param("error", errorCode(errorCode)).param("id", id).param("token", claimToken).update() == 1;
    }

    /** Not started before the lease margin: handed back as it was, no attempt counted (reference 11.2 "deferred"). */
    public boolean release(UUID id, UUID claimToken) {
        return jdbc.sql("UPDATE " + table + " SET status = 'PENDING', locked_until = NULL, claim_token = NULL"
                        + " WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", claimToken).update() == 1;
    }

    /** Failure retention: DEAD rows created before {@code olderThan} ago, at most {@code batch} per call. */
    public int purgeDead(Duration olderThan, int batch) {
        return jdbc.sql("DELETE FROM " + table + " WHERE id IN (SELECT id FROM " + table
                        + " WHERE status = 'DEAD' AND created_at < now() - make_interval(secs => :ageSeconds)"
                        + " ORDER BY created_at LIMIT :batch)")
                .param("ageSeconds", (double) olderThan.toSeconds()).param("batch", batch).update();
    }

    /** Creation time of the oldest row still to deliver (PENDING or PUBLISHING); empty when there is none. */
    public Optional<Instant> oldestPending() {
        Timestamp oldest = jdbc.sql("SELECT min(created_at) FROM " + table + " WHERE status <> 'DEAD'")
                .query(Timestamp.class).optional().orElse(null);
        return Optional.ofNullable(oldest).map(Timestamp::toInstant);
    }

    /** Rows that gave up, waiting for the failure retention. */
    public long countDead() {
        return jdbc.sql("SELECT count(*) FROM " + table + " WHERE status = 'DEAD'").query(Long.class).single();
    }

    private static OutboxRow row(ResultSet rs, int rowNum) throws SQLException {
        return new OutboxRow(rs.getObject("id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
                rs.getInt("retry_count"), rs.getTimestamp("created_at").toInstant(),
                rs.getObject("claim_token", UUID.class));
    }

    private static void requireWord(String value, String name) {
        if (value == null || !WORD.matcher(value).matches()) {
            throw new IllegalArgumentException(name + " must be a fixed lower-case word");
        }
    }

    /** Exception type names only (reference 11.2 last_error_code): never a message, which could quote the row. */
    private static String errorCode(String code) {
        return code != null && ERROR_CODE.matcher(code).matches() ? code : "UNKNOWN";
    }
}
