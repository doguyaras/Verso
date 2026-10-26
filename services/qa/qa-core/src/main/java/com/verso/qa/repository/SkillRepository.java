package com.verso.qa.repository;

import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.SkillOrigin;
import com.verso.qa.api.dto.SkillStaleReason;
import com.verso.qa.api.dto.SkillState;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The skills' tables in the qa schema (ADR-0053). Every write runs inside {@link QaDatabase#inTransaction}; state
 * changes are conditional updates (the expected state is part of the WHERE clause), so two instances or two admins
 * cannot both apply a transition. A version and everything under it is written once. No text of a skill is ever
 * logged; the row records print ids only.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillRepository {

    private static final String SKILL_COLUMNS = "id, name, state, stale_reason, version, origin, origin_cluster_id, "
            + "scope_collections, created_by, cycle, required_approvers, approved_at, created_at, updated_at";

    /** One skill row (the current version's number and name). */
    public record SkillRow(UUID id, String name, SkillState state, SkillStaleReason staleReason, int version,
                           SkillOrigin origin, UUID originClusterId, List<UUID> scopeCollections, String createdBy,
                           int cycle, int requiredApprovers, Instant approvedAt, Instant createdAt, Instant updatedAt) {

        @Override
        public String toString() {
            return "SkillRow[id=" + id + ", state=" + state + ", version=" + version + "]";
        }
    }

    /** A new skill. */
    public record NewSkill(String name, SkillOrigin origin, UUID originClusterId, List<UUID> scopeCollections,
                           String createdBy, int requiredApprovers, Instant now) {

        @Override
        public String toString() {
            return "NewSkill[origin=" + origin + "]";
        }
    }

    public record VersionRow(int version, String name, String description, String reason, boolean condensed,
                             Instant createdAt) {

        @Override
        public String toString() {
            return "VersionRow[version=" + version + "]";
        }
    }

    public record StepRow(int position, String instruction, boolean grounded) {

        @Override
        public String toString() {
            return "StepRow[position=" + position + "]";
        }
    }

    /** One bound chunk of a step. */
    public record SourceRow(int position, UUID chunkId, SourcePage page, String unit, String contentSha256) {
    }

    public record GoldenRow(int position, String question, SourcePage page, String fact, String embeddingModel) {

        @Override
        public String toString() {
            return "GoldenRow[position=" + position + "]";
        }
    }

    /** A golden case of an APPROVED skill for the nightly check. */
    public record EvalCase(UUID skillId, int version, int position, String question, SourcePage page,
                           String embeddingModel) {

        @Override
        public String toString() {
            return "EvalCase[skillId=" + skillId + ", position=" + position + "]";
        }
    }

    /** A skill candidate of the serving lookup: the nearest trigger's cosine similarity to the question. */
    public record Candidate(UUID skillId, double similarity) {
    }

    private final QaDatabase db;

    public SkillRepository(QaDatabase db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- writes

    public UUID insert(NewSkill skill) {
        return db.jdbc().sql("INSERT INTO qa.skill (name, state, version, origin, origin_cluster_id, scope_collections, "
                        + "created_by, required_approvers, created_at, updated_at) VALUES (:name, 'DRAFT', 1, :origin, "
                        + ":cluster, CAST(:scope AS uuid[]), :createdBy, :required, :now, :now) RETURNING id")
                .param("name", skill.name()).param("origin", skill.origin().name())
                .param("cluster", skill.originClusterId())
                .param("scope", skill.scopeCollections().stream().map(UUID::toString).toArray(String[]::new))
                .param("createdBy", skill.createdBy()).param("required", skill.requiredApprovers())
                .param("now", Timestamp.from(skill.now())).query(UUID.class).single();
    }

    public void insertVersion(UUID skillId, int version, String name, String description, String reason,
                              boolean condensed, Instant now) {
        db.jdbc().sql("INSERT INTO qa.skill_version (skill_id, version, name, description, reason, condensed, created_at) "
                        + "VALUES (:skill, :version, :name, :description, :reason, :condensed, :now)")
                .param("skill", skillId).param("version", version).param("name", name)
                .param("description", description).param("reason", reason).param("condensed", condensed)
                .param("now", Timestamp.from(now)).update();
    }

    public void insertStep(UUID skillId, int version, int position, String instruction, boolean grounded) {
        db.jdbc().sql("INSERT INTO qa.skill_step (skill_id, version, position, instruction, grounded) "
                        + "VALUES (:skill, :version, :position, :instruction, :grounded)")
                .param("skill", skillId).param("version", version).param("position", position)
                .param("instruction", instruction).param("grounded", grounded).update();
    }

    public void insertSource(UUID skillId, int version, int position, UUID chunkId, SourcePage page, String unit,
                             String contentSha256) {
        db.jdbc().sql("INSERT INTO qa.skill_step_source (skill_id, version, position, chunk_id, document_id, page, unit, "
                        + "content_sha256) VALUES (:skill, :version, :position, :chunk, :document, :page, :unit, :hash) "
                        + "ON CONFLICT DO NOTHING")
                .param("skill", skillId).param("version", version).param("position", position).param("chunk", chunkId)
                .param("document", page.documentId()).param("page", page.pageNumber()).param("unit", unit)
                .param("hash", contentSha256).update();
    }

    public void insertTrigger(UUID skillId, int version, int position, String question, float[] embedding,
                              String model) {
        db.jdbc().sql("INSERT INTO qa.skill_trigger (skill_id, version, position, question, embedding, embedding_model) "
                        + "VALUES (:skill, :version, :position, :question, CAST(:embedding AS extensions.vector), :model)")
                .param("skill", skillId).param("version", version).param("position", position)
                .param("question", question).param("embedding", KnowledgeCardRepository.vectorLiteral(embedding))
                .param("model", model).update();
    }

    public void insertGolden(UUID skillId, int version, int position, String question, SourcePage page, String fact,
                             float[] embedding, String model) {
        db.jdbc().sql("INSERT INTO qa.skill_golden_case (skill_id, version, position, question, document_id, page, fact, "
                        + "embedding, embedding_model) VALUES (:skill, :version, :position, :question, :document, :page, "
                        + ":fact, CAST(:embedding AS extensions.vector), :model)")
                .param("skill", skillId).param("version", version).param("position", position)
                .param("question", question).param("document", page.documentId()).param("page", page.pageNumber())
                .param("fact", fact).param("embedding", KnowledgeCardRepository.vectorLiteral(embedding))
                .param("model", model).update();
    }

    public void insertEvent(UUID skillId, int version, SkillState from, SkillState to, String reason, Instant at) {
        db.jdbc().sql("INSERT INTO qa.skill_event (skill_id, version, from_state, to_state, reason, created_at) "
                        + "VALUES (:skill, :version, :from, :to, :reason, :at)")
                .param("skill", skillId).param("version", version).param("from", from == null ? null : from.name())
                .param("to", to.name()).param("reason", reason).param("at", Timestamp.from(at)).update();
    }

    /**
     * A new version becomes current: back to DRAFT, a new approval cycle, the new name and scope. Only from the state
     * the caller read under the row lock.
     */
    public boolean newVersion(UUID skillId, SkillState from, int version, String name, List<UUID> scope, Instant now) {
        return db.jdbc().sql("UPDATE qa.skill SET version = :version, name = :name, state = 'DRAFT', stale_reason = NULL, "
                        + "approved_at = NULL, cycle = cycle + 1, scope_collections = CAST(:scope AS uuid[]), "
                        + "updated_at = :now WHERE id = :skill AND state = :from AND version = :version - 1")
                .param("skill", skillId).param("from", from.name()).param("version", version).param("name", name)
                .param("scope", scope.stream().map(UUID::toString).toArray(String[]::new))
                .param("now", Timestamp.from(now)).update() == 1;
    }

    /** False when this account already approved the skill in this cycle. */
    public boolean insertApproval(UUID skillId, int cycle, String approverId, Instant at) {
        return db.jdbc().sql("INSERT INTO qa.skill_approval (skill_id, cycle, approver_id, approved_at) "
                        + "VALUES (:skill, :cycle, :approver, :at) ON CONFLICT DO NOTHING")
                .param("skill", skillId).param("cycle", cycle).param("approver", approverId)
                .param("at", Timestamp.from(at)).update() == 1;
    }

    public int countApprovals(UUID skillId, int cycle) {
        return db.jdbc().sql("SELECT count(*) FROM qa.skill_approval WHERE skill_id = :skill AND cycle = :cycle")
                .param("skill", skillId).param("cycle", cycle).query(Integer.class).single();
    }

    public boolean approvedBy(UUID skillId, int cycle, String approverId) {
        return db.jdbc().sql("SELECT count(*) FROM qa.skill_approval WHERE skill_id = :skill AND cycle = :cycle "
                        + "AND approver_id = :approver")
                .param("skill", skillId).param("cycle", cycle).param("approver", approverId)
                .query(Integer.class).single() > 0;
    }

    /** DRAFT → APPROVED; false when it was not DRAFT (of this version) any more. */
    public boolean markApproved(UUID skillId, int version, Instant now) {
        return db.jdbc().sql("UPDATE qa.skill SET state = 'APPROVED', approved_at = :now, updated_at = :now "
                        + "WHERE id = :skill AND state = 'DRAFT' AND version = :version")
                .param("skill", skillId).param("version", version).param("now", Timestamp.from(now)).update() == 1;
    }

    /** DRAFT or APPROVED → STALE; empty when it was already stale or archived (idempotent). */
    public Optional<SkillState> markStale(UUID skillId, SkillStaleReason reason, Instant now) {
        return db.jdbc().sql("UPDATE qa.skill s SET state = 'STALE', stale_reason = :reason, updated_at = :now "
                        + "FROM (SELECT id, state FROM qa.skill WHERE id = :skill FOR UPDATE) old "
                        + "WHERE s.id = old.id AND old.state IN ('DRAFT', 'APPROVED') RETURNING old.state")
                .param("skill", skillId).param("reason", reason.name()).param("now", Timestamp.from(now))
                .query((rs, n) -> SkillState.valueOf(rs.getString(1))).optional();
    }

    public Optional<SkillState> archive(UUID skillId, Instant now) {
        return db.jdbc().sql("UPDATE qa.skill s SET state = 'ARCHIVED', stale_reason = NULL, updated_at = :now "
                        + "FROM (SELECT id, state FROM qa.skill WHERE id = :skill FOR UPDATE) old "
                        + "WHERE s.id = old.id AND old.state <> 'ARCHIVED' RETURNING old.state")
                .param("skill", skillId).param("now", Timestamp.from(now))
                .query((rs, n) -> SkillState.valueOf(rs.getString(1))).optional();
    }

    /** Archived skills unchanged since the cutoff, with everything that cascades from them. */
    public int purgeArchived(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.skill WHERE id IN (SELECT id FROM qa.skill WHERE state = 'ARCHIVED' "
                        + "AND updated_at < :cutoff LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    public void recordEval(UUID runId, UUID skillId, int version, int position, String outcome, Integer rank) {
        db.jdbc().sql("INSERT INTO qa.skill_eval_result (run_id, skill_id, version, position, outcome, rank) "
                        + "VALUES (:run, :skill, :version, :position, :outcome, :rank) ON CONFLICT DO NOTHING")
                .param("run", runId).param("skill", skillId).param("version", version).param("position", position)
                .param("outcome", outcome).param("rank", rank).update();
    }

    // ---------------------------------------------------------------- reads

    public Optional<SkillRow> find(UUID skillId) {
        return db.jdbc().sql("SELECT " + SKILL_COLUMNS + " FROM qa.skill WHERE id = :skill")
                .param("skill", skillId).query(SkillRepository::skill).optional();
    }

    public Optional<SkillRow> lock(UUID skillId) {
        return db.jdbc().sql("SELECT " + SKILL_COLUMNS + " FROM qa.skill WHERE id = :skill FOR UPDATE")
                .param("skill", skillId).query(SkillRepository::skill).optional();
    }

    /** The live (not archived) skill of this name. */
    public Optional<SkillRow> findLive(String name) {
        return db.jdbc().sql("SELECT " + SKILL_COLUMNS + " FROM qa.skill WHERE name = :name AND state <> 'ARCHIVED'")
                .param("name", name).query(SkillRepository::skill).optional();
    }

    public boolean nameTaken(String name, UUID except) {
        return db.jdbc().sql("SELECT EXISTS (SELECT 1 FROM qa.skill WHERE name = :name AND state <> 'ARCHIVED' "
                        + "AND (CAST(:except AS uuid) IS NULL OR id <> :except))")
                .param("name", name).param("except", except).query(Boolean.class).single();
    }

    public List<SkillRow> list(SkillState state, int page, int size) {
        return db.jdbc().sql("SELECT " + SKILL_COLUMNS + " FROM qa.skill WHERE (CAST(:state AS text) IS NULL "
                        + "OR state = :state) ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("state", state == null ? null : state.name()).param("size", size)
                .param("offset", (long) page * size).query(SkillRepository::skill).list();
    }

    public long count(SkillState state) {
        return db.jdbc().sql("SELECT count(*) FROM qa.skill WHERE (CAST(:state AS text) IS NULL OR state = :state)")
                .param("state", state == null ? null : state.name()).query(Long.class).single();
    }

    /** The APPROVED skills by name (MCP listing). */
    public List<SkillRow> approved(int limit) {
        return db.jdbc().sql("SELECT " + SKILL_COLUMNS + " FROM qa.skill WHERE state = 'APPROVED' ORDER BY name LIMIT :limit")
                .param("limit", limit).query(SkillRepository::skill).list();
    }

    public Optional<VersionRow> version(UUID skillId, int version) {
        return db.jdbc().sql("SELECT version, name, description, reason, condensed, created_at FROM qa.skill_version "
                        + "WHERE skill_id = :skill AND version = :version")
                .param("skill", skillId).param("version", version).query(SkillRepository::version).optional();
    }

    public List<VersionRow> versions(UUID skillId) {
        return db.jdbc().sql("SELECT version, name, description, reason, condensed, created_at FROM qa.skill_version "
                        + "WHERE skill_id = :skill ORDER BY version")
                .param("skill", skillId).query(SkillRepository::version).list();
    }

    /** The current versions' descriptions of several skills (one statement for a page of the list). */
    public Map<UUID, String> descriptions(Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) return Map.of();
        Map<UUID, String> found = new LinkedHashMap<>();
        db.jdbc().sql("SELECT v.skill_id, v.description FROM qa.skill_version v JOIN qa.skill s ON s.id = v.skill_id "
                        + "AND s.version = v.version WHERE v.skill_id IN (:skills)")
                .param("skills", skillIds)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list()
                .forEach(e -> found.put(e.getKey(), e.getValue()));
        return found;
    }

    public List<StepRow> steps(UUID skillId, int version) {
        return db.jdbc().sql("SELECT position, instruction, grounded FROM qa.skill_step WHERE skill_id = :skill "
                        + "AND version = :version ORDER BY position")
                .param("skill", skillId).param("version", version)
                .query((rs, n) -> new StepRow(rs.getInt(1), rs.getString(2), rs.getBoolean(3))).list();
    }

    public List<SourceRow> sources(UUID skillId, int version) {
        return db.jdbc().sql("SELECT position, chunk_id, document_id, page, unit, content_sha256 FROM qa.skill_step_source "
                        + "WHERE skill_id = :skill AND version = :version ORDER BY position, document_id, page, chunk_id")
                .param("skill", skillId).param("version", version)
                .query((rs, n) -> new SourceRow(rs.getInt(1), rs.getObject(2, UUID.class),
                        new SourcePage(rs.getObject(3, UUID.class), rs.getInt(4)), rs.getString(5), rs.getString(6)))
                .list();
    }

    public List<String> triggers(UUID skillId, int version) {
        return db.jdbc().sql("SELECT question FROM qa.skill_trigger WHERE skill_id = :skill AND version = :version "
                        + "ORDER BY position")
                .param("skill", skillId).param("version", version).query(String.class).list();
    }

    public List<GoldenRow> golden(UUID skillId, int version) {
        return db.jdbc().sql("SELECT position, question, document_id, page, fact, embedding_model FROM qa.skill_golden_case "
                        + "WHERE skill_id = :skill AND version = :version ORDER BY position")
                .param("skill", skillId).param("version", version)
                .query((rs, n) -> new GoldenRow(rs.getInt(1), rs.getString(2),
                        new SourcePage(rs.getObject(3, UUID.class), rs.getInt(4)), rs.getString(5), rs.getString(6)))
                .list();
    }

    public Optional<float[]> goldenEmbedding(UUID skillId, int version, int position) {
        return db.jdbc().sql("SELECT embedding::text FROM qa.skill_golden_case WHERE skill_id = :skill "
                        + "AND version = :version AND position = :position")
                .param("skill", skillId).param("version", version).param("position", position)
                .query(String.class).optional().map(KnowledgeCardRepository::parseVector);
    }

    /** The state history, oldest first: {version, from, to, reason, at}. */
    public List<Object[]> events(UUID skillId) {
        return db.jdbc().sql("SELECT version, from_state, to_state, reason, created_at FROM qa.skill_event "
                        + "WHERE skill_id = :skill ORDER BY created_at, id")
                .param("skill", skillId)
                .query((rs, n) -> new Object[] {rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getTimestamp(5).toInstant()}).list();
    }

    /** The source documents of the current versions of several skills (one statement). */
    public Map<UUID, Set<UUID>> sourceDocuments(Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) return Map.of();
        Map<UUID, Set<UUID>> found = new LinkedHashMap<>();
        db.jdbc().sql("SELECT DISTINCT x.skill_id, x.document_id FROM qa.skill_step_source x JOIN qa.skill s "
                        + "ON s.id = x.skill_id AND s.version = x.version WHERE x.skill_id IN (:skills)")
                .param("skills", skillIds)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list()
                .forEach(e -> found.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(e.getValue()));
        return found;
    }

    /** The live skills (draft or approved) whose current version is bound to this document. */
    public List<UUID> liveSkillsCiting(UUID documentId) {
        return db.jdbc().sql("SELECT DISTINCT s.id FROM qa.skill s JOIN qa.skill_step_source x ON x.skill_id = s.id "
                        + "AND x.version = s.version WHERE x.document_id = :document AND s.state IN ('DRAFT', 'APPROVED') "
                        + "ORDER BY s.id")
                .param("document", documentId).query(UUID.class).list();
    }

    /**
     * The serving lookup (ADR-0053): APPROVED skills of the current embedding model by their nearest trigger, exact
     * cosine order, with that similarity. No vector index: a customer keeps tens to hundreds of skills.
     */
    public List<Candidate> nearestApproved(float[] question, String model, int limit) {
        String vector = KnowledgeCardRepository.vectorLiteral(question);
        return db.jdbc().sql("SELECT t.skill_id, max(1 - (t.embedding <=> CAST(:q AS extensions.vector))) AS similarity "
                        + "FROM qa.skill_trigger t JOIN qa.skill s ON s.id = t.skill_id AND s.version = t.version "
                        + "WHERE s.state = 'APPROVED' AND t.embedding_model = :model GROUP BY t.skill_id "
                        + "ORDER BY similarity DESC, t.skill_id LIMIT :limit")
                .param("q", vector).param("model", model).param("limit", limit)
                .query((rs, n) -> new Candidate(rs.getObject(1, UUID.class), rs.getDouble(2))).list();
    }

    /** The golden cases of the APPROVED skills' current versions, oldest approval first. */
    public List<EvalCase> approvedCases(int limit) {
        return db.jdbc().sql("SELECT g.skill_id, g.version, g.position, g.question, g.document_id, g.page, "
                        + "g.embedding_model FROM qa.skill_golden_case g JOIN qa.skill s ON s.id = g.skill_id "
                        + "AND s.version = g.version WHERE s.state = 'APPROVED' "
                        + "ORDER BY s.approved_at, g.skill_id, g.position LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> new EvalCase(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3), rs.getString(4),
                        new SourcePage(rs.getObject(5, UUID.class), rs.getInt(6)), rs.getString(7))).list();
    }

    // ---------------------------------------------------------------- mapping

    private static SkillRow skill(ResultSet rs, int n) throws SQLException {
        String reason = rs.getString("stale_reason");
        return new SkillRow(rs.getObject("id", UUID.class), rs.getString("name"), SkillState.valueOf(rs.getString("state")),
                reason == null ? null : SkillStaleReason.valueOf(reason), rs.getInt("version"),
                SkillOrigin.valueOf(rs.getString("origin")), rs.getObject("origin_cluster_id", UUID.class),
                uuids(rs.getArray("scope_collections")), rs.getString("created_by"), rs.getInt("cycle"),
                rs.getInt("required_approvers"), instant(rs.getTimestamp("approved_at")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private static VersionRow version(ResultSet rs, int n) throws SQLException {
        return new VersionRow(rs.getInt(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getBoolean(5),
                rs.getTimestamp(6).toInstant());
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(o -> (UUID) o).toList();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
