package com.verso.document.api.dto;

import java.util.UUID;

/**
 * What {@code DocumentIngestion.submit} did (ADR-0038).
 *
 * @param outcome    see {@link Outcome}
 * @param documentId the stored document for CREATED and REPLAYED, otherwise null
 */
public record IngestionResult(Outcome outcome, UUID documentId) {

    public enum Outcome {
        /** A new PENDING document; the ingestion worker parses and embeds it. */
        CREATED,
        /** The same idempotency key was stored before: that document, nothing changed. */
        REPLAYED,
        /** Empty file: permanent until the file changes. */
        EMPTY,
        /** Larger than the pipeline's limit: permanent until the file changes. */
        TOO_LARGE,
        /** Not a PDF, DOCX, TXT or MD by name and content (ADR-0016): permanent until the file changes. */
        UNSUPPORTED,
        /** The collection is gone: the connector's target must be fixed. */
        COLLECTION_MISSING,
        /** The collection holds its maximum number of documents. */
        COLLECTION_FULL,
        /** This source already has the maximum number of documents waiting for ingestion: try later (backpressure). */
        QUEUE_FULL;

        /** A result that holds no document. */
        public boolean rejected() {
            return this != CREATED && this != REPLAYED;
        }
    }

    public static IngestionResult rejected(Outcome outcome) {
        return new IngestionResult(outcome, null);
    }
}
