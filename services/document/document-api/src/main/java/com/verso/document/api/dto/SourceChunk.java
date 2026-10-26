package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * One chunk of a cited page with the SHA-256 of its text (ADR-0049): the hash binding of a verified answer. A verified
 * answer stays valid only while every chunk it was bound to still exists with the same hash. Documents are immutable
 * per id in Verso (a changed file is a new document), so a missing hash means the source was deleted, replaced or
 * re-chunked.
 *
 * <p>{@code fileName}, {@code collectionName} and {@code content} are filled only for a reader's lookup (the reader may
 * read them); the system lookup carries ids and hashes only. The content is document text and is never logged
 * (llm-rules 2.1); {@link #toString()} prints ids only.
 *
 * @param contentSha256 lower-case hex SHA-256 of the chunk's UTF-8 text, computed by the database
 * @param collectionId  the collection holding the document, null for a personal document
 */
public record SourceChunk(UUID chunkId, UUID documentId, int pageNumber, SourceUnit unit, String contentSha256,
                          UUID collectionId, String fileName, String collectionName, String content) {

    public SourcePage page() {
        return new SourcePage(documentId, pageNumber);
    }

    @Override
    public String toString() {
        return "SourceChunk[chunkId=" + chunkId + ", documentId=" + documentId + ", page=" + pageNumber + "]";
    }
}
