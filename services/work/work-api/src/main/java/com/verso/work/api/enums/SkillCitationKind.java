package com.verso.work.api.enums;

/** What a numbered source of a run is (ADR-0057). */
public enum SkillCitationKind {
    /** A passage of a document the caller may read (re-read with the caller's access on every view). */
    PASSAGE,
    /** A part of the caller's own long-text input (meeting notes, a thread). */
    INPUT
}
