package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One line of GET /v1/admin/activity (ADR-0058). Never an account id, a user name or document, question or answer
 * text: the actor is a team, a role or a system component, and a document or card name appears in {@code title} only
 * when the requesting admin may read it (ADR-0028).
 *
 * @param id           "{source}.{uuid}", the key of GET /v1/admin/activity/{id}
 * @param category     RUN, BACKGROUND, HOOK, KNOWLEDGE or APPROVAL
 * @param type         a fixed word of the source (CONNECTOR_SYNC, GOLDEN_EVAL, CARD_TO_VERIFY, ...); clients show
 *                     unknown words as they are
 * @param status       RUNNING, SUCCEEDED, PARTIAL, FAILED, REGRESSION, PENDING or INFO
 * @param finishedAt   null while running or pending
 * @param detail       one line of counts or a fixed reason; may be null
 * @param collectionId the shared collection; null when none or not readable
 * @param link         the panel screen to open; may be null
 * @param audit        the audit-trail reference; may be null
 * @param attention    counts towards the status strip's "N iş dikkat istiyor"
 */
public record ActivityItemResponse(String id, String category, String type, String status, Instant startedAt,
                                   Instant finishedAt, Actor actor, String title, String detail, UUID collectionId,
                                   Link link, AuditRef audit, boolean attention) {

    /** {@code kind}: SYSTEM, ROLE or TEAM. */
    public record Actor(String kind, String label) {
    }

    public record Link(String screen, UUID targetId) {
    }

    /** {@code targetType} is an AuditTargetType word, {@code action} an AuditAction word (or null). */
    public record AuditRef(String targetType, UUID targetId, String action) {
    }
}
