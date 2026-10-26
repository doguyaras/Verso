package com.verso.document.systemmodel;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/**
 * When a document's system model must be extracted again (ADR-0054): incremental per document, never a global rebuild.
 * The stored model remembers the extractor version and a digest of its chunks' SHA-256 hashes in order; a model is
 * current only while both still match. A changed chunk (a re-ingestion), an added or removed chunk, or a new extractor
 * version makes it stale, and only that document is extracted again.
 */
public final class SystemModelPlan {

    /** What is stored for one document; null when it was never extracted. */
    public record Stored(int extractorVersion, String chunksSha256) {
    }

    private SystemModelPlan() {
    }

    public static boolean needsExtraction(Stored stored, int version, String digest) {
        return stored == null || stored.extractorVersion() != version || !stored.chunksSha256().equals(digest);
    }

    /** SHA-256 over the chunk hashes in chunk order, one per line: the document's content as the model saw it. */
    public static String digest(List<String> chunkHashes) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String hash : chunkHashes) {
                sha.update(hash.getBytes(StandardCharsets.US_ASCII));
                sha.update((byte) '\n');
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every JRE", e);
        }
    }
}
