package com.verso.work.api.enums;

/** What a form field holds (ADR-0057). */
public enum SkillInputType {
    /** One line of text. */
    TEXT,
    /** Multi-line text (notes, a thread, a requirement list); also the source of INPUT citations. */
    LONG_TEXT,
    /** One of the field's options. */
    SELECT,
    /** Document ids the caller may read; grounding is limited to them. */
    DOCUMENTS,
    /** Collection ids the caller may read; grounding is limited to them. */
    COLLECTIONS
}
