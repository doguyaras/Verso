package com.verso.work.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The runs and their numbered sources in the schema "work" (ADR-0057). Every read and write of a run by its owner
 * names the owner in the statement itself: another user's run is the same "not found" as a missing one. State changes
 * are conditional updates (a finished run cannot be finished twice, a lost run cannot be revived), never
 * check-then-act (AGENTS.md 10).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillRunRepository {

    /** One run row; the JSON columns as text (the service maps them). */
    public record RunRow(UUID id, String ownerSub, String skillId, String skillVersion, UUID customSkillId,
                         String definitionJson, String status, String stage, Integer step, int stepCount,
                         String failureCode, String inputsJson, String outputJson, String checksJson, boolean edited,
                         Integer rating, String modelId, Instant createdAt, Instant finishedAt, Instant approvedAt) {

        @Override
        public String toString() {
            return "RunRow[id=" + id + ", status=" + status + "]";
        }
    }

    /** One numbered source; no text and no file name. */
    public record SourceRow(int number, String kind, UUID passageId, UUID documentId, Integer page, String unit,
                            UUID collectionId, String inputKey, Integer start, Integer end) {
    }

    private static final String COLUMNS = "id, owner_sub, skill_id, skill_version, custom_skill_id, definition::text AS d, "
            + "status, stage, step, step_count, failure_code, inputs::text AS i, output::text AS o, checks::text AS c, "
            + "edited, rating, model_id, created_at, finished_at, approved_at";

    private final WorkDatabase db;

    public SkillRunRepository(WorkDatabase db) {
        this.db = db;
    }

    /**
     * False when the owner already has a RUNNING run: the partial unique index uq_skill_run_running decides inside the
     * statement (ON CONFLICT DO NOTHING). Catching the unique violation instead would leave the caller's transaction
     * aborted: PostgreSQL refuses every later statement of it, and its COMMIT is a rollback.
     */
    public boolean insertRunning(UUID id, String owner, String skillId, String version, UUID customSkillId,
                                 String definitionJson, int stepCount, String inputsJson, Duration lease) {
        return db.jdbc().sql("""
                INSERT INTO work.skill_run (id, owner_sub, skill_id, skill_version, custom_skill_id, definition,
                    step_count, inputs, lease_until)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, now() + make_interval(secs => ?))
                ON CONFLICT (owner_sub) WHERE status = 'RUNNING' DO NOTHING""")
                .params(id, owner, skillId, version, customSkillId, definitionJson, stepCount, inputsJson,
                        lease.toSeconds())
                .update() == 1;
    }

    /** Moves a RUNNING run to a stage and renews its lease; false when it is no longer RUNNING (deleted, swept). */
    public boolean stage(UUID id, String stage, Integer step, Duration lease) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET stage = ?, step = ?, lease_until = now() + make_interval(secs => ?),
                    updated_at = now()
                WHERE id = ? AND status = 'RUNNING'""")
                .params(stage, step, lease.toSeconds(), id).update() == 1;
    }

    /** Stores the output and the sources of a RUNNING run as a DRAFT; false when it is no longer RUNNING. */
    public boolean finish(UUID id, String outputJson, String checksJson, String modelId, List<SourceRow> sources) {
        int updated = db.jdbc().sql("""
                UPDATE work.skill_run SET status = 'DRAFT', stage = 'FINISHED', step = NULL, lease_until = NULL,
                    output = ?::jsonb, checks = ?::jsonb, model_id = ?, finished_at = now(), updated_at = now()
                WHERE id = ? AND status = 'RUNNING'""")
                .params(outputJson, checksJson, modelId, id).update();
        if (updated != 1) return false;
        for (SourceRow s : sources) {
            db.jdbc().sql("""
                    INSERT INTO work.skill_run_source (run_id, number, kind, passage_id, document_id, page, unit,
                        collection_id, input_key, start_offset, end_offset)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
                    .params(id, s.number(), s.kind(), s.passageId(), s.documentId(), s.page(), s.unit(),
                            s.collectionId(), s.inputKey(), s.start(), s.end())
                    .update();
        }
        return true;
    }

    /** Fails a RUNNING run with a fixed word; false when it is no longer RUNNING. */
    public boolean fail(UUID id, String failureCode, String modelId) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET status = 'FAILED', stage = 'FINISHED', step = NULL, lease_until = NULL,
                    failure_code = ?, model_id = ?, finished_at = now(), updated_at = now()
                WHERE id = ? AND status = 'RUNNING'""")
                .params(failureCode, modelId, id).update() == 1;
    }

    /** Runs whose instance died: their lease passed. Failed as INTERRUPTED, at most {@code limit}; their ids. */
    public List<UUID> failLost(int limit) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET status = 'FAILED', stage = 'FINISHED', step = NULL, lease_until = NULL,
                    failure_code = 'INTERRUPTED', finished_at = now(), updated_at = now()
                WHERE id IN (SELECT id FROM work.skill_run WHERE status = 'RUNNING' AND lease_until < now()
                             ORDER BY lease_until LIMIT ? FOR UPDATE SKIP LOCKED)
                RETURNING id""")
                .param(limit).query(UUID.class).list();
    }

    public Optional<RunRow> find(UUID id, String owner) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM work.skill_run WHERE id = ? AND owner_sub = ?")
                .params(id, owner).query(SkillRunRepository::row).optional();
    }

    public List<RunRow> page(String owner, int page, int size) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM work.skill_run WHERE owner_sub = ? "
                        + "ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?")
                .params(owner, size, (long) page * size).query(SkillRunRepository::row).list();
    }

    public long count(String owner) {
        return db.jdbc().sql("SELECT count(*) FROM work.skill_run WHERE owner_sub = ?").param(owner)
                .query(Long.class).single();
    }

    public List<SourceRow> sources(UUID runId) {
        return db.jdbc().sql("""
                SELECT number, kind, passage_id, document_id, page, unit, collection_id, input_key, start_offset,
                    end_offset
                FROM work.skill_run_source WHERE run_id = ? ORDER BY number""")
                .param(runId)
                .query((rs, n) -> new SourceRow(rs.getInt(1), rs.getString(2), rs.getObject(3, UUID.class),
                        rs.getObject(4, UUID.class), (Integer) rs.getObject(5), rs.getString(6),
                        rs.getObject(7, UUID.class), rs.getString(8), (Integer) rs.getObject(9),
                        (Integer) rs.getObject(10)))
                .list();
    }

    /** The owner's edit of a finished run: a draft again. False when the run is not the owner's or not finished. */
    public boolean replaceOutput(UUID id, String owner, String outputJson, String checksJson) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET output = ?::jsonb, checks = ?::jsonb, edited = true, status = 'DRAFT',
                    approved_at = NULL, updated_at = now()
                WHERE id = ? AND owner_sub = ? AND status IN ('DRAFT', 'APPROVED')""")
                .params(outputJson, checksJson, id, owner).update() == 1;
    }

    /** DRAFT to APPROVED; an approved run stays approved (idempotent). False when not finished or not the owner's. */
    public boolean approve(UUID id, String owner) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET status = 'APPROVED', approved_at = coalesce(approved_at, now()),
                    updated_at = now()
                WHERE id = ? AND owner_sub = ? AND status IN ('DRAFT', 'APPROVED')""")
                .params(id, owner).update() == 1;
    }

    public boolean rate(UUID id, String owner, int rating) {
        return db.jdbc().sql("""
                UPDATE work.skill_run SET rating = ?, updated_at = now()
                WHERE id = ? AND owner_sub = ? AND status IN ('DRAFT', 'APPROVED')""")
                .params(rating, id, owner).update() == 1;
    }

    public boolean delete(UUID id, String owner) {
        return db.jdbc().sql("DELETE FROM work.skill_run WHERE id = ? AND owner_sub = ?").params(id, owner).update() == 1;
    }

    /** Retention: at most {@code batch} runs created before {@code cutoff}, any status; SKIP LOCKED across instances. */
    public int deleteCreatedBefore(Instant cutoff, int batch) {
        return db.jdbc().sql("""
                DELETE FROM work.skill_run WHERE id IN (
                    SELECT id FROM work.skill_run WHERE created_at < ? ORDER BY created_at LIMIT ?
                    FOR UPDATE SKIP LOCKED)""")
                .params(Timestamp.from(cutoff), batch).update();
    }

    /** KVKK erasure (ADR-0034): every run of the subject; their sources go with them. */
    public long deleteOwner(String owner) {
        return db.jdbc().sql("DELETE FROM work.skill_run WHERE owner_sub = ?").param(owner).update();
    }

    private static RunRow row(ResultSet rs, int n) throws SQLException {
        return new RunRow(rs.getObject("id", UUID.class), rs.getString("owner_sub"), rs.getString("skill_id"),
                rs.getString("skill_version"), rs.getObject("custom_skill_id", UUID.class), rs.getString("d"),
                rs.getString("status"), rs.getString("stage"), (Integer) toInt(rs.getObject("step")),
                rs.getInt("step_count"), rs.getString("failure_code"), rs.getString("i"), rs.getString("o"),
                rs.getString("c"), rs.getBoolean("edited"), toInt(rs.getObject("rating")), rs.getString("model_id"),
                instant(rs.getTimestamp("created_at")), instant(rs.getTimestamp("finished_at")),
                instant(rs.getTimestamp("approved_at")));
    }

    private static Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
