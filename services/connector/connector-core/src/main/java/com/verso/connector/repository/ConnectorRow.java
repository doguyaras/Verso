package com.verso.connector.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** One row of connector.connector (ADR-0038). Holds no secret: {@code credentialRef} is a secret file's name. */
public record ConnectorRow(UUID id, String name, String host, int port, String share, String pathPrefix, String domain,
                           String username, String credentialRef, UUID collectionId, List<String> includeGlobs,
                           List<String> excludeGlobs, long maxFileBytes, int scheduleMinutes, boolean enabled,
                           boolean deleting, boolean massDeletionConfirmed, String createdBy, Instant createdAt,
                           Instant updatedAt, Instant nextRunAt, Instant syncRequestedAt, Instant lockedUntil,
                           UUID claimToken, int consecutiveFailures, Instant lastSuccessAt, Instant lastFailureAt,
                           String lastErrorCode) {

    /** Ids only (security-rules 4): never the host, the share, the account or the credential name. */
    @Override
    public String toString() {
        return "ConnectorRow[id=" + id + "]";
    }
}
