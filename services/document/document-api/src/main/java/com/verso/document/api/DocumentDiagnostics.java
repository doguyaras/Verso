package com.verso.document.api;

import com.verso.document.api.dto.ChunkPairScan;
import com.verso.document.api.dto.SharedDocument;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * In-process contract for the company diagnostics of the learning knowledge layer (ADR-0048 phase 2, ADR-0052): the
 * shared documents with their collections and ages, collection names, and near-duplicate chunk pairs for the offline
 * contradiction scan. Only the qa module's scheduled diagnostics job and its admin report call it; no method takes or
 * returns anything about an account, and personal documents never appear (diagnostics cover shared knowledge only).
 * No model call in any method.
 */
public interface DocumentDiagnostics {

    /** At most this many shared documents per call. */
    int MAX_DOCUMENTS = 50_000;

    /**
     * READY documents in shared collections (ADR-0028), oldest first, at most {@code limit}: ids, collection ids and
     * creation times only (in Verso a changed file is a new document, so the creation time is the last change).
     */
    List<SharedDocument> sharedDocuments(int limit);

    /** The names of those collections (verso-admin manages every collection, ADR-0028); unknown ids are absent. */
    Map<UUID, String> collectionNames(Collection<UUID> collectionIds);

    /**
     * The contradiction scan's input (ADR-0052): for at most {@code maxChunks} chunks of shared READY documents that
     * contain a digit, taken in id order after {@code after}, their nearest chunks of <b>other</b> shared documents (at
     * most {@code neighbours} each, same embedding model) whose cosine similarity is at least {@code minSimilarity}.
     *
     * <p>The pairs carry the chunk text, read without any reader filter, for the caller's in-memory comparison of
     * numbers and codes only: the caller must not store, log or return it (it keeps ids, pages and hashes; a text is
     * shown only after a reader's own lookup through {@link DocumentProvenance#sourceChunks}).
     *
     * @param after the cursor of the previous scan, null to start from the first chunk
     */
    ChunkPairScan similarChunkPairs(UUID after, int maxChunks, int neighbours, double minSimilarity);
}
