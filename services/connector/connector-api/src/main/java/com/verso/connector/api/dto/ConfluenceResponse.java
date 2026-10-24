package com.verso.connector.api.dto;

import com.verso.connector.api.enums.ConfluenceDeployment;
import java.time.Instant;
import java.util.List;

/**
 * The Confluence settings of a connector (ADR-0044); null on an SMB connector. No token: the connector's
 * {@code credentialRef} names its secret file.
 *
 * @param lastFullListingAt the last complete full listing (deletions, archived pages, restriction sweep); null: never
 */
public record ConfluenceResponse(String baseUrl, ConfluenceDeployment deployment, List<String> spaceKeys, String label,
                                 Long ancestorId, boolean includeAttachments, boolean skipRestricted, int pageSize,
                                 Instant lastFullListingAt) {
}
