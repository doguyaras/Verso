package com.verso.connector.api.dto;

import com.verso.connector.api.enums.SyncRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One sync run (ADR-0038): counts only, no file name or path.
 *
 * @param listingComplete the whole source was listed; only then are files missing from the listing deleted
 * @param deferred        changes left for the next run (per-run limits, ingestion backlog, retry backoff)
 */
public record SyncRunResponse(UUID id, SyncRunStatus status, Instant startedAt, Instant finishedAt, String errorCode,
                              boolean listingComplete, int listed, int added, int updated, int deleted, int unchanged,
                              int failed, int skipped, int deferred) {
}
