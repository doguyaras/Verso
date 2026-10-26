package com.verso.qa.api.dto;

/** Where a skill's draft came from (ADR-0053). */
public enum SkillOrigin {
    /** A cluster of recurring questions of the diagnostics (ADR-0052) with the verified cards around it. */
    CLUSTER,
    /** An admin chose verified cards and their own sourced answers. */
    MANUAL
}
