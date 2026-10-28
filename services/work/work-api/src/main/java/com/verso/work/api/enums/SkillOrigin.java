package com.verso.work.api.enums;

/** Where a task skill comes from (ADR-0057). Unrelated to the procedures' origin of ADR-0053. */
public enum SkillOrigin {
    /** Shipped with Verso under skills/ in the repository. */
    BUILT_IN,
    /** Authored for this installation by a verso-admin (the consulting team), approved before use. */
    CUSTOM
}
