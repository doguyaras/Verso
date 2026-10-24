package com.verso.audit.repository;

import com.verso.audit.api.dto.AuditEntryResponse;
import com.verso.audit.api.dto.AuditSubjectEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of the audit log on the module's own AuditDatabase (schema "audit", role svc_audit, ADR-0026). The role can
 * INSERT and SELECT here and nothing else: there is no UPDATE and no DELETE in this class, and the retention is one
 * database function that refuses to touch the recent past. {@code id} and {@code occurred_at} are never sent: the
 * database assigns them, so a caller cannot back-date a row.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditEventRepository {

    private static final String COLUMNS = """
            id, occurred_at, actor_sub, actor_roles, action, outcome, target_type, target_id, conversation_id,
            detail_code, model_id, citation_document_ids, trace_id, mode""";
    private static final String RANGE = " FROM audit.event WHERE occurred_at >= :from AND occurred_at < :to";

    /**
     * A row to append. Everything is an id, a fixed word or the opaque token subject. {@code eventId} is the delivery
     * key (ADR-0039): unique, so a relay that delivers the same outbox row twice writes one row.
     */
    public record NewEntry(String actorSub, List<String> actorRoles, AuditAction action, AuditOutcome outcome,
                           AuditTargetType targetType, UUID targetId, UUID conversationId, String detailCode,
                           String modelId, List<UUID> citationDocumentIds, String traceId, String mode,
                           UUID eventId) {
    }

    /** Position of the last exported row; the next batch starts after it. */
    public record Cursor(Instant occurredAt, UUID id) {
    }

    private final AuditDatabase db;

    public AuditEventRepository(AuditDatabase db) {
        this.db = db;
    }

    public void insert(NewEntry e) {
        insertIfAbsent(e);
    }

    /**
     * Appends the row unless one with the same event id exists (ADR-0039). the duplicate guard needs only the INSERT
     * and SELECT the role has; no UPDATE, so a duplicate can never change a stored row.
     *
     * @return true when this call wrote the row
     */
    public boolean insertIfAbsent(NewEntry e) {
        return db.jdbc().sql("""
                        INSERT INTO audit.event (actor_sub, actor_roles, action, outcome, target_type, target_id,
                                                 conversation_id, detail_code, model_id, citation_document_ids,
                                                 trace_id, mode, event_id)
                        VALUES (:actor, CAST(:roles AS text[]), :action, :outcome, :targetType, :targetId,
                                :conversationId, :detailCode, :modelId, CAST(:cited AS uuid[]), :traceId, :mode,
                                :eventId)
                        ON CONFLICT (event_id) DO NOTHING""")
                .param("actor", e.actorSub()).param("roles", e.actorRoles().toArray(String[]::new))
                .param("action", e.action().name()).param("outcome", e.outcome().name())
                .param("targetType", e.targetType().name()).param("targetId", e.targetId())
                .param("conversationId", e.conversationId()).param("detailCode", e.detailCode())
                .param("modelId", e.modelId()).param("cited", e.citationDocumentIds().toArray(UUID[]::new))
                .param("traceId", e.traceId()).param("mode", e.mode()).param("eventId", e.eventId())
                .update() == 1;
    }

    /** Newest first; {@code to} is exclusive. */
    public List<AuditEntryResponse> page(Instant from, Instant to, AuditAction action, int offset, int limit) {
        String sql = "SELECT " + COLUMNS + RANGE + actionClause(action)
                + " ORDER BY occurred_at DESC, id DESC LIMIT :limit OFFSET :offset";
        return range(db.jdbc().sql(sql), from, to, action).param("limit", limit).param("offset", offset)
                .query(AuditEventRepository::map).list();
    }

    public long count(Instant from, Instant to, AuditAction action) {
        String sql = "SELECT count(*)" + RANGE + actionClause(action);
        return range(db.jdbc().sql(sql), from, to, action).query(Long.class).single();
    }

    /** Rows in the range, counted up to {@code cap} + 1 so the export knows whether it will be cut. */
    public long countUpTo(Instant from, Instant to, int cap) {
        return db.jdbc().sql("SELECT count(*) FROM (SELECT 1" + RANGE + " LIMIT :cap) t")
                .param("from", ts(from)).param("to", ts(to)).param("cap", (long) cap + 1)
                .query(Long.class).single();
    }

    /** Oldest first, {@code limit} rows after {@code after} (keyset: no offset, no long-lived cursor). */
    public List<AuditEntryResponse> exportBatch(Instant from, Instant to, Cursor after, int limit) {
        String sql = "SELECT " + COLUMNS + RANGE + (after == null ? "" : " AND (occurred_at, id) > (:afterAt, :afterId)")
                + " ORDER BY occurred_at, id LIMIT :limit";
        JdbcClient.StatementSpec spec = db.jdbc().sql(sql).param("from", ts(from)).param("to", ts(to))
                .param("limit", limit);
        if (after != null) spec = spec.param("afterAt", ts(after.occurredAt())).param("afterId", after.id());
        return spec.query(AuditEventRepository::map).list();
    }

    /**
     * The retention job's lock for the current transaction (reference 10.4: pg_try_advisory_xact_lock, "someone else
     * is on it, skip"); released at commit, so it also works behind a transaction-mode pooler.
     */
    public boolean tryLockRetention() {
        return Boolean.TRUE.equals(db.jdbc().sql("SELECT pg_try_advisory_xact_lock(hashtextextended(:key, 0))")
                .param("key", "audit.retention").query(Boolean.class).single());
    }

    /**
     * Deletes up to {@code limit} rows older than the cutoff through audit.purge_expired, the only way the application
     * role can remove a row. The function itself refuses a cutoff younger than 30 days (V1__audit_event.sql).
     */
    public int purgeExpired(Instant cutoff, int limit) {
        return db.jdbc().sql("SELECT audit.purge_expired(CAST(:cutoff AS timestamptz), :limit)")
                .param("cutoff", ts(cutoff)).param("limit", limit).query(Integer.class).single();
    }

    /** Rows naming {@code sub} as the actor (ADR-0034; index idx_event_actor_occurred, V2). */
    public long countByActor(String sub) {
        return db.jdbc().sql("SELECT count(*) FROM audit.event WHERE actor_sub = :sub")
                .param("sub", sub).query(Long.class).single();
    }

    /** Oldest first, {@code limit} rows of {@code sub} as the actor after {@code after} (keyset). Ids and words only. */
    public List<AuditSubjectEvent> byActor(String sub, Cursor after, int limit) {
        String sql = "SELECT id, occurred_at, action, outcome, target_type, target_id FROM audit.event "
                + "WHERE actor_sub = :sub" + (after == null ? "" : " AND (occurred_at, id) > (:afterAt, :afterId)")
                + " ORDER BY occurred_at, id LIMIT :limit";
        JdbcClient.StatementSpec spec = db.jdbc().sql(sql).param("sub", sub).param("limit", limit);
        if (after != null) spec = spec.param("afterAt", ts(after.occurredAt())).param("afterId", after.id());
        return spec.query((rs, row) -> new AuditSubjectEvent(rs.getObject("id", UUID.class),
                rs.getTimestamp("occurred_at").toInstant(), rs.getString("action"), rs.getString("outcome"),
                rs.getString("target_type"), rs.getObject("target_id", UUID.class))).list();
    }

    private static String actionClause(AuditAction action) {
        return action == null ? "" : " AND action = :action";
    }

    private static JdbcClient.StatementSpec range(JdbcClient.StatementSpec spec, Instant from, Instant to,
                                                  AuditAction action) {
        JdbcClient.StatementSpec ranged = spec.param("from", ts(from)).param("to", ts(to));
        return action == null ? ranged : ranged.param("action", action.name());
    }

    private static Timestamp ts(Instant instant) {
        return Timestamp.from(instant);
    }

    private static AuditEntryResponse map(ResultSet rs, int row) throws SQLException {
        return new AuditEntryResponse(rs.getObject("id", UUID.class), rs.getTimestamp("occurred_at").toInstant(),
                rs.getString("actor_sub"), Arrays.asList((String[]) rs.getArray("actor_roles").getArray()),
                AuditAction.valueOf(rs.getString("action")), AuditOutcome.valueOf(rs.getString("outcome")),
                AuditTargetType.valueOf(rs.getString("target_type")), rs.getObject("target_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("detail_code"), rs.getString("model_id"),
                uuids(rs.getArray("citation_document_ids")), rs.getString("trace_id"), rs.getString("mode"));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return Arrays.asList((UUID[]) array.getArray());
    }
}
