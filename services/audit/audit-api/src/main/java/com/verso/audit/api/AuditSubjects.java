package com.verso.audit.api;

import com.verso.audit.api.dto.AuditSubjectEvent;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * In-process contract for KVKK data-subject requests (ADR-0034): the audit rows in which a subject is the actor, as
 * ids and fixed words. Read only: the audit log is append-only and is never erased for a subject (ADR-0026); the
 * request itself is recorded as a new row. Refused to every caller without {@code verso-admin}, whichever module asks.
 */
public interface AuditSubjects {

    /** At most this many rows per call. */
    int MAX_BATCH = 1_000;

    /** How many rows name {@code sub} as the actor. */
    long countByActor(String sub);

    /**
     * Rows of {@code sub} as the actor, oldest first, after the keyset ({@code afterAt}, {@code afterId}); both null for
     * the first batch.
     */
    List<AuditSubjectEvent> byActor(String sub, Instant afterAt, UUID afterId, int limit);
}
