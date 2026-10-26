package com.verso.qa.api.dto;

/**
 * What a hook rule may do (ADR-0048 principle 5, ADR-0052). The complete list: a hook can never promote, delete or
 * change access; those are human decisions. The database enforces the same list (qa.hook_rule CHECK).
 */
public enum HookAction {
    /** An in-app notice for the installation's admins. */
    NOTIFY,
    /** An item waiting for an admin's review (a notice that stays open until read). */
    FLAG,
    /** A verified answer card to STALE: it is no longer served until a human makes a new one. */
    DEMOTE,
    /** The golden-set check (retrieval only, no model call), coalesced and run after a quiet period. */
    RUN_EVAL
}
