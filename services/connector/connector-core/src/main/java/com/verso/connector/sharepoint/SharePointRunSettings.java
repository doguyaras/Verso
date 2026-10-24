package com.verso.connector.sharepoint;

import com.verso.connector.domain.DeltaCheckpoint;
import com.verso.connector.domain.GlobSet;
import java.util.List;
import java.util.UUID;

/**
 * What one SharePoint run needs (ADR-0045), read from the connector row at claim time. No secret: the factory reads it
 * from the secret file named {@code credentialRef} just before the first request.
 *
 * @param folderPrefix the folder inside each library, normalised ("" for the whole library)
 * @param full         enumerate every library from scratch (deletions by absence); else continue from the stored
 *                     delta links
 * @param sweep        re-read every listed file's permissions (the periodic sweep); else only the files fetched
 * @param stored       the libraries' delta links and folders of the last applied run; empty with a full listing
 */
public record SharePointRunSettings(UUID connectorId, String siteUrl, String siteId, List<String> drives,
                                    String folderPrefix, GlobSet include, GlobSet exclude, boolean skipUnique,
                                    String tenantId, String clientId, String authMethod, String credentialRef,
                                    boolean full, boolean sweep, List<DeltaCheckpoint.Stored> stored) {

    public boolean certificate() {
        return "CERTIFICATE".equals(authMethod);
    }

    /** Ids only: never the site, the libraries, the folder, the app or the credential name (security-rules 4). */
    @Override
    public String toString() {
        return "SharePointRunSettings[connectorId=" + connectorId + ", full=" + full + ", sweep=" + sweep + "]";
    }
}
