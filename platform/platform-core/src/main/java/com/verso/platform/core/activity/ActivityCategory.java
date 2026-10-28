package com.verso.platform.core.activity;

/** The tabs of the activity centre (ADR-0058). */
public enum ActivityCategory {
    /** Skill and review runs started by a team or role (the skills module, when deployed). */
    RUN,
    /** Connector syncs, the nightly golden-set check, knowledge-health scans, system-model extraction. */
    BACKGROUND,
    /** What a hook rule did (ADR-0052): notifications and flags. */
    HOOK,
    /** Documents added, changed or deleted; verified cards gone stale. */
    KNOWLEDGE,
    /** Waits for a person: cards to verify, procedures, bulk deletions, contradictions. */
    APPROVAL
}
