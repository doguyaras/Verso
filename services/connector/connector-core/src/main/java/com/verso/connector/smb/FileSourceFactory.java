package com.verso.connector.smb;

import com.verso.connector.domain.SourceSettings;

/** Opens a {@link FileSource} for a connector's settings (ADR-0038); a seam for tests without a file server. */
public interface FileSourceFactory {

    /**
     * Checks the egress allowlist, reads the credential and connects.
     *
     * @throws SourceException fatal, with EGRESS_DENIED, CREDENTIAL_MISSING, SOURCE_UNREACHABLE, SOURCE_AUTH_FAILED,
     *                         SHARE_NOT_FOUND, PATH_NOT_FOUND or SOURCE_ERROR
     */
    FileSource open(SourceSettings settings);
}
