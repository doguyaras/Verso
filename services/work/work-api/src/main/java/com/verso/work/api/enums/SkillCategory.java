package com.verso.work.api.enums;

/** Gallery group of a skill (ADR-0057); the panel shows a Turkish label per value. */
public enum SkillCategory {
    /** Meeting notes, reports, e-mail threads. */
    PRODUCTIVITY,
    /** Tenders, RFP/RFQ responses, proposals. */
    SALES,
    /** Contracts and compliance pre-checks; always carries a "for expert review" disclaimer. */
    LEGAL_COMPLIANCE,
    /** Requirements, project health, pre-mortem. */
    PROJECT,
    /** Complaints, root causes, quality. */
    QUALITY_CUSTOMER,
    /** Procedures and SOP drafts. */
    PROCESS
}
