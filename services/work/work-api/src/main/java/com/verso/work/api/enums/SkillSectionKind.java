package com.verso.work.api.enums;

/** The shape of one output section (ADR-0057). */
public enum SkillSectionKind {
    /** A paragraph with its own evidence. */
    TEXT,
    /** A table: one row per finding, decision, risk or requirement, each row with evidence or "değerlendirilemez". */
    ROWS
}
