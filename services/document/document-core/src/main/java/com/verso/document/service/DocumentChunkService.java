package com.verso.document.service;

import com.verso.document.api.dto.DocumentChunkResponse;
import com.verso.document.api.dto.PageResponse;
import java.util.UUID;

/** GET /v1/admin/documents/{id}/chunks: the chunk inspector of verso-admin (ADR-0036). */
public interface DocumentChunkService {

    /**
     * One page of the chunks of a document the caller reads (ADR-0028 principals): managing a collection is not reading
     * it, for verso-admin too. Another or a missing document is DOCUMENT_NOT_FOUND.
     */
    PageResponse<DocumentChunkResponse> chunks(Caller caller, UUID documentId, int page, int size);
}
