package com.verso.document.api.enums;

/**
 * Lifecycle of an uploaded document (ADR-0003, ADR-0011). PENDING: stored, waiting for the ingestion worker.
 * PROCESSING: claimed by a worker (a lease; an expired one is claimed again). READY: pages, chunks and embeddings are
 * stored and the original PDF is gone. FAILED: see {@link DocumentFailureReason}; nothing but the metadata is kept.
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED
}
