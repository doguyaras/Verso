package com.verso.connector.api.dto;

import com.verso.connector.api.enums.SharePointAuthMethod;
import java.time.Instant;
import java.util.List;

/**
 * The SharePoint settings of a connector (ADR-0045); null on another type. No secret, no key: the connector's
 * {@code credentialRef} names its secret file. The folder and the filters are the connector's {@code pathPrefix},
 * {@code includeGlobs} and {@code excludeGlobs}.
 *
 * @param lastFullListingAt the last complete full listing (deletions by absence and the permission sweep); null: never
 */
public record SharePointResponse(String siteUrl, String siteId, List<String> drives, String tenantId, String clientId,
                                 SharePointAuthMethod authMethod, boolean skipUniquePermissions,
                                 Instant lastFullListingAt) {
}
