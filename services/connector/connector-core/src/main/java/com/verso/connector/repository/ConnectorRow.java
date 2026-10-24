package com.verso.connector.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of connector.connector (ADR-0038, ADR-0044). Holds no secret: {@code credentialRef} is a secret file's name.
 * SMB rows have no {@code confluence}; Confluence rows have no share, path prefix, domain or globs.
 *
 * @param syncCursor    Confluence: the newest "last modified" a complete listing fully applied (incremental sync)
 * @param fullListingAt Confluence: the last complete full listing (deletions and the restriction sweep)
 */
public record ConnectorRow(UUID id, String name, String host, int port, String share, String pathPrefix, String domain,
                           String username, String credentialRef, UUID collectionId, List<String> includeGlobs,
                           List<String> excludeGlobs, long maxFileBytes, int scheduleMinutes, boolean enabled,
                           boolean deleting, boolean massDeletionConfirmed, String createdBy, Instant createdAt,
                           Instant updatedAt, Instant nextRunAt, Instant syncRequestedAt, Instant lockedUntil,
                           UUID claimToken, int consecutiveFailures, Instant lastSuccessAt, Instant lastFailureAt,
                           String lastErrorCode, String type, Confluence confluence, Instant syncCursor,
                           Instant fullListingAt) {

    public static final String SMB = "SMB";
    public static final String CONFLUENCE = "CONFLUENCE";

    /** The Confluence settings of a CONFLUENCE row (ADR-0044); deployment is DATA_CENTER or CLOUD. */
    public record Confluence(String baseUrl, String deployment, List<String> spaceKeys, String label, Long ancestorId,
                             boolean includeAttachments, boolean skipRestricted, int pageSize) {
        @Override
        public String toString() {
            return "Confluence[deployment=" + deployment + "]";
        }
    }

    /** An SMB row (the shape before ADR-0044). */
    public ConnectorRow(UUID id, String name, String host, int port, String share, String pathPrefix, String domain,
                        String username, String credentialRef, UUID collectionId, List<String> includeGlobs,
                        List<String> excludeGlobs, long maxFileBytes, int scheduleMinutes, boolean enabled,
                        boolean deleting, boolean massDeletionConfirmed, String createdBy, Instant createdAt,
                        Instant updatedAt, Instant nextRunAt, Instant syncRequestedAt, Instant lockedUntil,
                        UUID claimToken, int consecutiveFailures, Instant lastSuccessAt, Instant lastFailureAt,
                        String lastErrorCode) {
        this(id, name, host, port, share, pathPrefix, domain, username, credentialRef, collectionId, includeGlobs,
                excludeGlobs, maxFileBytes, scheduleMinutes, enabled, deleting, massDeletionConfirmed, createdBy,
                createdAt, updatedAt, nextRunAt, syncRequestedAt, lockedUntil, claimToken, consecutiveFailures,
                lastSuccessAt, lastFailureAt, lastErrorCode, SMB, null, null, null);
    }

    public boolean isConfluence() {
        return CONFLUENCE.equals(type);
    }

    /** Ids only (security-rules 4): never the host, the share, the account, the site or the credential name. */
    @Override
    public String toString() {
        return "ConnectorRow[id=" + id + "]";
    }
}
