package com.verso.work.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;

/**
 * The installation's skill switches and the customer-authored skills with their versions (ADR-0057). Live ids and
 * slash commands are unique by partial indexes; a state change is a conditional update.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillCatalogRepository {

    /** A customer-authored skill with the definition of its current version (JSON text). */
    public record CustomRow(UUID id, String skillId, String slash, String state, int version, String definitionJson,
                            Instant createdAt, Instant updatedAt, Instant approvedAt) {

        @Override
        public String toString() {
            return "CustomRow[id=" + id + ", state=" + state + ", version=" + version + "]";
        }
    }

    private static final String CUSTOM = """
            SELECT c.id, c.skill_id, c.slash, c.state, c.current_version, v.definition::text AS d, c.created_at,
                c.updated_at, c.approved_at
            FROM work.custom_skill c
            JOIN work.custom_skill_version v ON v.custom_skill_id = c.id AND v.version = c.current_version""";

    private final WorkDatabase db;

    public SkillCatalogRepository(WorkDatabase db) {
        this.db = db;
    }

    /** Skill id to its switch; a skill without a row is enabled. */
    public Map<String, Boolean> switches() {
        Map<String, Boolean> map = new HashMap<>();
        db.jdbc().sql("SELECT skill_id, enabled FROM work.skill_setting")
                .query(rs -> {
                    map.put(rs.getString(1), rs.getBoolean(2));
                });
        return map;
    }

    public void setSwitch(String skillId, boolean enabled) {
        db.jdbc().sql("""
                INSERT INTO work.skill_setting (skill_id, enabled) VALUES (?, ?)
                ON CONFLICT (skill_id) DO UPDATE SET enabled = excluded.enabled, updated_at = now()""")
                .params(skillId, enabled).update();
    }

    /** Creates a DRAFT custom skill at version 1; empty when its id or slash command is taken by a live one. */
    public Optional<UUID> createCustom(String skillId, String slash, String definitionJson) {
        UUID id;
        try {
            id = db.jdbc().sql("INSERT INTO work.custom_skill (skill_id, slash) VALUES (?, ?) RETURNING id")
                    .params(skillId, slash).query(UUID.class).single();
        } catch (DuplicateKeyException e) {
            return Optional.empty();
        }
        db.jdbc().sql("INSERT INTO work.custom_skill_version (custom_skill_id, version, definition) VALUES (?, 1, ?::jsonb)")
                .params(id, definitionJson).update();
        return Optional.of(id);
    }

    /**
     * A new version of a live custom skill, a DRAFT again; false when it is archived or missing. Its id cannot change;
     * a slash command taken by another live skill is a duplicate key (the caller answers 409).
     */
    public boolean newVersion(UUID id, String slash, String definitionJson) {
        Integer version = db.jdbc().sql("""
                UPDATE work.custom_skill SET current_version = current_version + 1, slash = ?, state = 'DRAFT',
                    approved_at = NULL, updated_at = now()
                WHERE id = ? AND state <> 'ARCHIVED' RETURNING current_version""")
                .params(slash, id).query(Integer.class).optional().orElse(null);
        if (version == null) return false;
        db.jdbc().sql("INSERT INTO work.custom_skill_version (custom_skill_id, version, definition) VALUES (?, ?, ?::jsonb)")
                .params(id, version, definitionJson).update();
        return true;
    }

    /** DRAFT to APPROVED; false otherwise. */
    public boolean approve(UUID id) {
        return db.jdbc().sql("""
                UPDATE work.custom_skill SET state = 'APPROVED', approved_at = now(), updated_at = now()
                WHERE id = ? AND state = 'DRAFT'""").param(id).update() == 1;
    }

    /** Any live state to ARCHIVED; false when already archived or missing. */
    public boolean archive(UUID id) {
        return db.jdbc().sql("""
                UPDATE work.custom_skill SET state = 'ARCHIVED', approved_at = NULL, updated_at = now()
                WHERE id = ? AND state <> 'ARCHIVED'""").param(id).update() == 1;
    }

    public Optional<CustomRow> find(UUID id) {
        return db.jdbc().sql(CUSTOM + " WHERE c.id = ?").param(id).query(SkillCatalogRepository::custom).optional();
    }

    /** Every custom skill that is not archived, newest first. */
    public List<CustomRow> live() {
        return db.jdbc().sql(CUSTOM + " WHERE c.state <> 'ARCHIVED' ORDER BY c.created_at DESC")
                .query(SkillCatalogRepository::custom).list();
    }

    private static CustomRow custom(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new CustomRow(rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getString(4),
                rs.getInt(5), rs.getString(6), instant(rs.getTimestamp(7)), instant(rs.getTimestamp(8)),
                instant(rs.getTimestamp(9)));
    }

    private static Instant instant(Timestamp t) {
        return t == null ? null : t.toInstant();
    }
}
