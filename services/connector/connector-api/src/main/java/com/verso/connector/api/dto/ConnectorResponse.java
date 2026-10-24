package com.verso.connector.api.dto;

import com.verso.connector.api.enums.ConnectorHealth;
import com.verso.connector.api.enums.ConnectorType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A connector as verso-admin sees it (ADR-0038). There is no password field: {@code credentialRef} is the secret
 * file's name, never its content. {@code health} is derived from the last successful sync, not from the last run's
 * status alone, so an index that silently stopped shows STALE instead of an old "success".
 *
 * @param lastSuccessAt       end of the last run that listed the whole source and applied its changes; null: never
 * @param consecutiveFailures runs in a row that failed as a whole (connection, credentials, egress, collection)
 * @param lastErrorCode       fixed code of the last failed run or item, never a message
 * @param items               state counts of the files (Confluence: pages and attachments) this connector tracks
 * @param type                SMB, CONFLUENCE or SHAREPOINT; host, share and username are null where the type has none
 *                            (SHAREPOINT: host is the site's host, pathPrefix the folder inside each library)
 * @param confluence          the Confluence settings; null for another type
 * @param sharePoint          the SharePoint/OneDrive settings (ADR-0045); null for another type
 */
public record ConnectorResponse(UUID id, String name, String host, int port, String share, String pathPrefix,
                                String domain, String username, String credentialRef, UUID collectionId,
                                List<String> includeGlobs, List<String> excludeGlobs, long maxFileBytes,
                                int scheduleMinutes, boolean enabled, ConnectorHealth health, Instant lastSuccessAt,
                                Instant lastFailureAt, int consecutiveFailures, String lastErrorCode, Instant nextRunAt,
                                ItemCounts items, Instant createdAt, Instant updatedAt, ConnectorType type,
                                ConfluenceResponse confluence, SharePointResponse sharePoint) {

    /** How many tracked files are in each state. */
    public record ItemCounts(long synced, long failed, long skipped) {
    }
}
