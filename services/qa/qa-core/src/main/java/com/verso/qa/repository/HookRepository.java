package com.verso.qa.repository;

import com.verso.qa.api.dto.HookAction;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * Hook rules, notifications and the coalesced golden-set check request (ADR-0052). The rule set comes from the
 * migration; the application role can only switch a rule (no INSERT or DELETE on qa.hook_rule), and the database
 * refuses any action outside NOTIFY, FLAG, DEMOTE and RUN_EVAL.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HookRepository {

    /** A rule as stored. */
    public record Rule(String key, HookTrigger trigger, HookAction action, boolean enabled, boolean defaultEnabled,
                       Instant updatedAt) {
    }

    /** A notification as stored. */
    public record Notification(UUID id, String ruleKey, HookAction action, HookTrigger trigger, String subjectType,
                               UUID subjectId, String detail, KnowledgeHealthSeverity severity, Instant createdAt,
                               Instant readAt) {
    }

    /** A notification to store. */
    public record NewNotification(String ruleKey, HookAction action, HookTrigger trigger, String subjectType,
                                  UUID subjectId, String detail, KnowledgeHealthSeverity severity, String dedupeKey) {
    }

    private final QaDatabase db;

    public HookRepository(QaDatabase db) {
        this.db = db;
    }

    public List<Rule> rules() {
        return db.jdbc().sql("SELECT * FROM qa.hook_rule ORDER BY rule_key").query(HookRepository::rule).list();
    }

    /** The enabled rules of a trigger, in key order. */
    public List<Rule> enabledRules(HookTrigger trigger) {
        return db.jdbc().sql("SELECT * FROM qa.hook_rule WHERE trigger_type = :trigger AND enabled ORDER BY rule_key")
                .param("trigger", trigger.name()).query(HookRepository::rule).list();
    }

    public Optional<Rule> rule(String key) {
        return db.jdbc().sql("SELECT * FROM qa.hook_rule WHERE rule_key = :key").param("key", key)
                .query(HookRepository::rule).optional();
    }

    /** Switches a rule; only the enabled flag and the time change. False for an unknown key. */
    public boolean setEnabled(String key, boolean enabled, Instant now) {
        return db.jdbc().sql("UPDATE qa.hook_rule SET enabled = :enabled, updated_at = :now WHERE rule_key = :key")
                .param("key", key).param("enabled", enabled).param("now", Timestamp.from(now)).update() == 1;
    }

    /** The new notification's id, or empty when one with the same dedupe key exists (a repeated event). */
    public Optional<UUID> insertNotification(NewNotification notification, Instant now) {
        return db.jdbc().sql("INSERT INTO qa.hook_notification (rule_key, action, trigger_type, subject_type, subject_id, "
                        + "detail, severity, dedupe_key, created_at) VALUES (:rule, :action, :trigger, :type, :subject, "
                        + ":detail, :severity, :dedupe, :now) ON CONFLICT (dedupe_key) DO NOTHING RETURNING id")
                .param("rule", notification.ruleKey()).param("action", notification.action().name())
                .param("trigger", notification.trigger().name()).param("type", notification.subjectType())
                .param("subject", notification.subjectId()).param("detail", notification.detail())
                .param("severity", notification.severity().name()).param("dedupe", notification.dedupeKey())
                .param("now", Timestamp.from(now))
                .query(UUID.class).optional();
    }

    public List<Notification> notifications(boolean unreadOnly, int page, int size) {
        return db.jdbc().sql("SELECT * FROM qa.hook_notification WHERE (:all OR read_at IS NULL) "
                        + "ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("all", !unreadOnly).param("size", size).param("offset", (long) page * size)
                .query(HookRepository::notification).list();
    }

    public long countNotifications(boolean unreadOnly) {
        return db.jdbc().sql("SELECT count(*) FROM qa.hook_notification WHERE (:all OR read_at IS NULL)")
                .param("all", !unreadOnly).query(Long.class).single();
    }

    /** Unread FLAG items: waiting for review. */
    public long openFlags() {
        return db.jdbc().sql("SELECT count(*) FROM qa.hook_notification WHERE action = 'FLAG' AND read_at IS NULL")
                .query(Long.class).single();
    }

    public Optional<Notification> markRead(UUID id, Instant now) {
        return db.jdbc().sql("UPDATE qa.hook_notification SET read_at = coalesce(read_at, :now) WHERE id = :id RETURNING *")
                .param("id", id).param("now", Timestamp.from(now)).query(HookRepository::notification).optional();
    }

    public int purgeNotifications(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.hook_notification WHERE id IN (SELECT id FROM qa.hook_notification "
                        + "WHERE created_at < :cutoff ORDER BY created_at LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    /**
     * Whether an active golden case cites this document (a change of it may move recall): a verified card's, or an
     * APPROVED skill's current version (ADR-0053).
     */
    public boolean goldenCaseCites(UUID documentId) {
        return db.jdbc().sql("SELECT EXISTS (SELECT 1 FROM qa.golden_case g JOIN qa.knowledge_card_citation k "
                        + "ON k.card_id = g.card_id WHERE g.active AND k.document_id = :doc) "
                        + "OR EXISTS (SELECT 1 FROM qa.skill s JOIN qa.skill_step_source x ON x.skill_id = s.id "
                        + "AND x.version = s.version WHERE s.state = 'APPROVED' AND x.document_id = :doc)")
                .param("doc", documentId).query(Boolean.class).single();
    }

    /** Asks for a golden-set check: one pending request; a newer one moves its time (quiet period). */
    public void requestEval(String ruleKey, Instant now) {
        db.jdbc().sql("INSERT INTO qa.hook_eval_request (singleton, rule_key, requested_at) VALUES (true, :rule, :now) "
                        + "ON CONFLICT (singleton) DO UPDATE SET requested_at = EXCLUDED.requested_at, "
                        + "rule_key = EXCLUDED.rule_key")
                .param("rule", ruleKey).param("now", Timestamp.from(now)).update();
    }

    /** Takes the pending request when it is older than the cutoff (one instance wins: the row is deleted). */
    public Optional<String> takeEvalRequest(Instant cutoff) {
        return db.jdbc().sql("DELETE FROM qa.hook_eval_request WHERE requested_at <= :cutoff RETURNING rule_key")
                .param("cutoff", Timestamp.from(cutoff)).query(String.class).optional();
    }

    private static Rule rule(ResultSet rs, int n) throws SQLException {
        return new Rule(rs.getString("rule_key"), HookTrigger.valueOf(rs.getString("trigger_type")),
                HookAction.valueOf(rs.getString("action")), rs.getBoolean("enabled"), rs.getBoolean("default_enabled"),
                rs.getTimestamp("updated_at").toInstant());
    }

    private static Notification notification(ResultSet rs, int n) throws SQLException {
        Timestamp read = rs.getTimestamp("read_at");
        return new Notification(rs.getObject("id", UUID.class), rs.getString("rule_key"),
                HookAction.valueOf(rs.getString("action")), HookTrigger.valueOf(rs.getString("trigger_type")),
                rs.getString("subject_type"), rs.getObject("subject_id", UUID.class), rs.getString("detail"),
                KnowledgeHealthSeverity.valueOf(rs.getString("severity")), rs.getTimestamp("created_at").toInstant(),
                read == null ? null : read.toInstant());
    }
}
