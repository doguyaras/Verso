package com.verso.qa.api.dto;

/** The events a hook rule reacts to (ADR-0052). Each reaches the rule engine through an outbox or a scheduled check. */
public enum HookTrigger {
    /** A shared document was deleted, replaced or moved (document outbox, document.changed). */
    DOCUMENT_CHANGED,
    /** The diagnostics found a new cluster of uncertain questions (at least k distinct askers). */
    CLUSTER_EMERGED,
    /** A cluster of the previous run grew markedly. */
    CLUSTER_GROWING,
    /** Two shared documents state different numbers or codes about the same thing. */
    CONTRADICTION_FOUND,
    /** A verified answer card went STALE. */
    CARD_STALE,
    /** A verified answer card passed the dispute threshold. */
    CARD_DISPUTED,
    /** The golden-set check raised its alert (recall drop or source drift). */
    GOLDEN_RECALL_DROP,
    /** A verified answer card's verification runs out soon (scheduled check). */
    VERIFICATION_DUE,
    /** A skill (procedure) went STALE: a bound source was deleted, replaced, moved or changed (ADR-0053). */
    SKILL_STALE
}
