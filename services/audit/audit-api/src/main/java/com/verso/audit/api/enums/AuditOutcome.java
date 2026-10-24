package com.verso.audit.api.enums;

/** How the audited operation ended. Failed operations that changed nothing (404, 400) are not audited. */
public enum AuditOutcome {
    SUCCESS,
    /** The caller was authenticated but not allowed (403). */
    DENIED
}
