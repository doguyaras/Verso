package com.verso.connector.domain;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * What a sync needs to reach and filter one source (ADR-0038), read from the connector row at claim time. The password
 * is not here: the client reads it from the secret file named {@code credentialRef} just before connecting.
 *
 * @param pathPrefix normalised by {@link SourcePaths#normalizePrefix}
 */
public record SourceSettings(UUID connectorId, String host, int port, String share, String pathPrefix, String domain,
                             String username, String credentialRef, GlobSet include, GlobSet exclude,
                             long maxFileBytes) {

    /** Whether a listed path is in scope: a supported extension, included (or no include list) and not excluded. */
    public boolean inScope(String relativePath) {
        if (!SourcePaths.isCandidate(SourcePaths.fileName(relativePath))) return false;
        if (!include.isEmpty() && !include.matches(relativePath)) return false;
        return !exclude.matches(relativePath);
    }

    /**
     * The idempotency key of one version of one file: the same file with the same content always maps to the same
     * document, so a retry after a crash between "document stored" and "item recorded" finds it instead of adding a
     * second copy (operation-consistency 2: deterministic id + unique).
     */
    public static UUID contentKey(UUID connectorId, String relativePath, String sha256) {
        return UUID.nameUUIDFromBytes(("verso:connector:" + connectorId + ":" + relativePath + ":" + sha256)
                .getBytes(StandardCharsets.UTF_8));
    }

    /** Never the credential name or the host in logs; ids only (security-rules 4). */
    @Override
    public String toString() {
        return "SourceSettings[connectorId=" + connectorId + "]";
    }
}
