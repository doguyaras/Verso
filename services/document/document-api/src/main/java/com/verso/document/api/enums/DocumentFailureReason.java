package com.verso.document.api.enums;

/**
 * Why ingestion gave up (ADR-0011). Every value except PROCESSING_FAILED is permanent: the same file would fail again,
 * so it is not retried. Clients map these to their own messages; Verso never returns parser or model text.
 */
public enum DocumentFailureReason {
    /** The bytes are not a readable PDF. */
    NOT_A_PDF,
    /** Password protected: the text cannot be read without a password Verso does not ask for. */
    ENCRYPTED,
    /** More pages than verso.document.max-pages. */
    TOO_MANY_PAGES,
    /** More extracted text than verso.document.max-text-chars. */
    TOO_MUCH_TEXT,
    /** No extractable text, e.g. a scanned PDF (OCR is out of scope). */
    NO_TEXT,
    /** A temporary failure (embedding model, database) persisted through every retry, or the worker kept dying. */
    PROCESSING_FAILED
}
