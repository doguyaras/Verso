package com.verso.document.api;

import com.verso.document.api.dto.IngestionRequest;
import com.verso.document.api.dto.IngestionResult;
import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * In-process contract for a connector that mirrors an external source into a collection (ADR-0038). The document
 * module keeps the whole pipeline: format detection and the ADR-0016 limits, the stored file, parsing, chunking,
 * embeddings and the reader rows copied from the collection's grants (ADR-0028). The caller only hands over bytes.
 *
 * <p>No user is behind these calls (a background sync), so no token is checked. Each document change is audited in the
 * same transaction (outbox, ADR-0039) with the actor {@code system:{@value #AUDIT_COMPONENT}}: CONNECTOR_DOCUMENT_ADDED and
 * CONNECTOR_DOCUMENT_DELETED, ids and fixed words only. Only modules that list {@code document :: api} reach this interface,
 * and only the connector module calls it (ADR-0038, ArchitectureRulesTest).
 */
public interface DocumentIngestion {

    /** The audit actor of these changes: {@code system:connector-sync}. */
    String AUDIT_COMPONENT = "connector-sync";

    /** The largest file the pipeline accepts (verso.document.max-file-size, ADR-0016). */
    long maxFileBytes();

    /** Whether the collection exists (a connector is created or synced only into an existing one). */
    boolean collectionExists(UUID collectionId);

    /**
     * Stores the file as a new PENDING document of the collection, owned by {@code request.sourceAccount()}, and, in the
     * same transaction, deletes {@code request.replaces()} when it is still a document of that collection (a changed
     * file: the old version goes with the commit that adds the new one). Idempotent on
     * {@code (sourceAccount, idempotencyKey)}: a repeat returns the document of the first call with
     * {@link IngestionResult.Outcome#REPLAYED} and changes nothing.
     */
    IngestionResult submit(IngestionRequest request);

    /** At most this many ids per {@link #present} call. */
    int MAX_PRESENT_BATCH = 1_000;

    /**
     * Those of {@code documentIds} that are still documents of the collection: a document someone deleted or moved in
     * Verso is then added again from the source at the next sync instead of staying silently missing (ADR-0038).
     */
    Set<UUID> present(UUID collectionId, Collection<UUID> documentIds);

    /**
     * Deletes the document from the collection with its file, pages, chunks, embeddings, search vectors and reader rows
     * (ON DELETE CASCADE, ADR-0034). Idempotent: false when it is already gone or no longer in that collection.
     *
     * @param reason the audit detail, a fixed UPPER_SNAKE word (SOURCE_DELETED, SOURCE_UNREADABLE, CONNECTOR_DELETED)
     */
    boolean delete(UUID collectionId, UUID documentId, String reason);
}
