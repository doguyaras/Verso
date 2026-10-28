package com.verso.work.api.enums;

/** Life cycle of a customer-authored skill (ADR-0057): a new version is a draft until a verso-admin approves it. */
public enum CustomSkillState {
    DRAFT,
    APPROVED,
    ARCHIVED
}
