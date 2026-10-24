package com.verso.connector.api.dto;

import com.verso.connector.api.enums.ListingMode;
import com.verso.connector.api.enums.SyncRunStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * One sync run (ADR-0038): counts only, no file name or path.
 *
 * @param listingComplete the listing had no gap (an unreadable folder, a cut, a failed restriction check); only a
 *                        complete FULL listing deletes files missing from it
 * @param deferred        changes left for the next run (per-run limits, ingestion backlog, retry backoff, held deletions)
 * @param listingMode     FULL (the whole source, deletions possible) or INCREMENTAL (changes only, never deletes)
 * @param pendingDeletions exactly how many deletions the deletion safety held back in this run (0: none); they wait for
 *                        an admin's confirmMassDeletion. Null on runs recorded before this field existed
 */
public record SyncRunResponse(UUID id, SyncRunStatus status, Instant startedAt, Instant finishedAt, String errorCode,
                              boolean listingComplete, int listed, int added, int updated, int deleted, int unchanged,
                              int failed, int skipped, int deferred, ListingMode listingMode,
                              Integer pendingDeletions) {
}
