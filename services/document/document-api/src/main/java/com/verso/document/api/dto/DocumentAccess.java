package com.verso.document.api.dto;

import java.util.UUID;

/**
 * What a reader may know about a document another module refers to (ADR-0023, ADR-0028): a history citation resolves
 * its document id here on read. A document that no longer exists has no entry at all.
 *
 * @param accessible     the reader's principals still reach the document; when false, every other field is null: the
 *                       reader learns that it exists, not what it is called or where it lives
 * @param fileName       the document's name, only when accessible; never logged (llm-rules 2.1)
 * @param collectionId   the collection holding it, null for a personal document or when not accessible
 * @param collectionName that collection's name, under the same conditions
 */
public record DocumentAccess(UUID documentId, boolean accessible, String fileName, UUID collectionId,
                             String collectionName) {

    public static DocumentAccess notAccessible(UUID documentId) {
        return new DocumentAccess(documentId, false, null, null, null);
    }
}
