package com.verso.connector.confluence;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What one Confluence run needs (ADR-0044), read from the connector row at claim time. No token: the factory reads it
 * from the secret file named {@code credentialRef} just before the first request.
 *
 * @param deployment DATA_CENTER or CLOUD
 * @param username   the Cloud account e-mail; null for Data Center (a personal access token needs none)
 * @param full       list every page (deletions, restriction sweep); else only pages changed since {@code cursor}
 * @param cursor     null with a full listing
 */
public record ConfluenceRunSettings(UUID connectorId, String baseUrl, String deployment, List<String> spaceKeys,
                                    String label, Long ancestorId, boolean includeAttachments, boolean skipRestricted,
                                    int pageSize, String username, String credentialRef, boolean full, Instant cursor) {

    public boolean cloud() {
        return "CLOUD".equals(deployment);
    }

    /** Ids only: never the site, the account, the spaces or the credential name (security-rules 4). */
    @Override
    public String toString() {
        return "ConfluenceRunSettings[connectorId=" + connectorId + ", full=" + full + "]";
    }
}
