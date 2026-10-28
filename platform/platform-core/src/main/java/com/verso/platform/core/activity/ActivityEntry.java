package com.verso.platform.core.activity;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One line of the activity centre (ADR-0058).
 *
 * @param id          the source-local id (a run's id, a card's id, or a name-based UUID of a daily aggregate)
 * @param category    the tab the entry belongs to
 * @param type        a fixed word of the source (CONNECTOR_SYNC, GOLDEN_EVAL, CARD_TO_VERIFY, ...)
 * @param status      where it stands
 * @param startedAt   when it started (or, for a pending item, since when it waits)
 * @param finishedAt  null while running or pending
 * @param actor       a team, a role or a system component; never a person
 * @param title       a short Turkish summary; a document or card name only when the viewer may read it
 * @param detail      one line of counts or a fixed reason; may be null
 * @param collectionId the shared collection the entry belongs to; null when none or not readable
 * @param link        where the panel opens it; may be null
 * @param audit       the audit-trail reference; may be null when the action is not audited
 * @param attention   whether it counts towards "N iş dikkat istiyor"
 */
public record ActivityEntry(UUID id, ActivityCategory category, String type, ActivityStatus status, Instant startedAt,
                            Instant finishedAt, Actor actor, String title, String detail, UUID collectionId, Link link,
                            AuditRef audit, boolean attention) {

    private static final Pattern WORD = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern SCREEN = Pattern.compile("[a-z][a-z0-9-]{1,30}");
    public static final int MAX_TITLE = 200;
    public static final int MAX_DETAIL = 300;

    public ActivityEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(actor, "actor");
        if (type == null || !WORD.matcher(type).matches()) throw new IllegalArgumentException("type must be a fixed word");
        title = clip(Objects.requireNonNull(title, "title"), MAX_TITLE);
        detail = detail == null ? null : clip(detail, MAX_DETAIL);
    }

    /**
     * Who did it, at the level the activity centre may show (ADR-0023: no per-user analytics).
     *
     * @param kind  SYSTEM for a background component, ROLE for a Verso role, TEAM for a collection or a group
     * @param label a short Turkish label ("Bağlayıcı senkronu", "Yönetici rolü", "BT destek alanı")
     */
    public record Actor(ActorKind kind, String label) {
        public Actor {
            Objects.requireNonNull(kind, "kind");
            label = clip(Objects.requireNonNull(label, "label"), 80);
        }

        public static Actor system(String label) {
            return new Actor(ActorKind.SYSTEM, label);
        }

        public static Actor role(String label) {
            return new Actor(ActorKind.ROLE, label);
        }

        public static Actor team(String label) {
            return new Actor(ActorKind.TEAM, label);
        }
    }

    public enum ActorKind { SYSTEM, ROLE, TEAM }

    /** A panel route word and an optional id ("connectors", "knowledge", ...); the panel validates both again. */
    public record Link(String screen, UUID targetId) {
        public Link {
            if (screen == null || !SCREEN.matcher(screen).matches()) throw new IllegalArgumentException("bad screen");
        }
    }

    /** Points at the audit log: the target type of AuditTargetType, its id and the audited action, all fixed words. */
    public record AuditRef(String targetType, UUID targetId, String action) {
        public AuditRef {
            if (targetType == null || !WORD.matcher(targetType).matches()) throw new IllegalArgumentException("bad type");
            if (action != null && !WORD.matcher(action).matches()) throw new IllegalArgumentException("bad action");
        }
    }

    private static String clip(String text, int max) {
        String line = text.replaceAll("[\\p{Cntrl}\\p{Cf}]", " ").strip();
        return line.length() <= max ? line : line.substring(0, max - 1) + "…";
    }
}
