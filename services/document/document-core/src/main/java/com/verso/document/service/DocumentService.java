package com.verso.document.service;

import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import java.util.UUID;

/**
 * Use cases of /v1/documents. The caller always comes from the validated token, never the request (ADR-0028: account,
 * groups and the admin role).
 */
public interface DocumentService {

    /**
     * Stores the document as PENDING in the caller's personal space ({@code collectionId} null) or in a collection
     * (verso-admin only); with an idempotency key a repeated upload returns the first document.
     */
    DocumentResponse upload(Caller caller, UUID collectionId, String fileName, byte[] content, UUID idempotencyKey);

    /**
     * Without a collection: every document the caller may read, personal and shared. With one: that collection's
     * documents, when the caller may read it or is verso-admin.
     */
    PageResponse<DocumentResponse> list(Caller caller, UUID collectionId, int page, int size);

    /** A document the caller may read, or a collection document for verso-admin. */
    DocumentResponse get(Caller caller, UUID documentId);

    /** The caller's personal document, or a collection document for verso-admin. */
    void delete(Caller caller, UUID documentId);

    /** verso-admin: to a collection, or to the uploader's personal space ({@code collectionId} null). */
    DocumentResponse move(Caller caller, UUID documentId, UUID collectionId);
}
