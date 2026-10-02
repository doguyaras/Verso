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
    /** More text or content than the limits allow (verso.document.max-text-chars, max-page-chars, max-content-bytes). */
    TOO_MUCH_TEXT,
    /** Uses a structure Verso does not process safely, e.g. an unusual content stream encoding. */
    UNSUPPORTED_PDF,
    /** No extractable text, e.g. a scanned PDF (OCR is out of scope). */
    NO_TEXT,
    /** A temporary failure (embedding model, database) persisted through every retry, or the worker kept dying. */
    PROCESSING_FAILED
}
