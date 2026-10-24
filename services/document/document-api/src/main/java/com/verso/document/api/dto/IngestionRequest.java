package com.verso.document.api.dto;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One file a connector hands to the document pipeline (ADR-0038).
 *
 * @param sourceAccount  the owner recorded on the document: {@code connector:<connector id>}, never a person's
 *                       {@code sub}; the per-account queue limit then applies per connector
 * @param collectionId   the target collection; its grants become the document's readers (ADR-0028)
 * @param fileName       the file's name at the source (sanitised by the document module, never logged)
 * @param content        the bytes, at most {@code DocumentIngestion.maxFileBytes()}
 * @param idempotencyKey derived from the source, the path and the content hash, so a retry after a crash finds the
 *                       document of the first attempt instead of adding a second one
 * @param replaces       the document of the previous version of this file, deleted in the same transaction; null
 */
public record IngestionRequest(String sourceAccount, UUID collectionId, String fileName, byte[] content,
                               UUID idempotencyKey, UUID replaces) {

    private static final Pattern SOURCE_ACCOUNT =
            Pattern.compile("connector:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    public IngestionRequest {
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (sourceAccount == null || !SOURCE_ACCOUNT.matcher(sourceAccount).matches()) {
            throw new IllegalArgumentException("sourceAccount must be connector:<uuid>");
        }
    }

    /** The source account of a connector. */
    public static String accountOf(UUID connectorId) {
        return "connector:" + connectorId;
    }

    /** Never the bytes or the file name (security-rules 4). */
    @Override
    public String toString() {
        return "IngestionRequest[collectionId=" + collectionId + ", sizeBytes=" + content.length + "]";
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof IngestionRequest r && sourceAccount.equals(r.sourceAccount)
                && collectionId.equals(r.collectionId) && idempotencyKey.equals(r.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sourceAccount, collectionId, idempotencyKey);
    }
}
