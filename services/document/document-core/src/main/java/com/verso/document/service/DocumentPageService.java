package com.verso.document.service;

import com.verso.document.api.dto.DocumentPageResponse;
import java.util.UUID;

/** GET /v1/documents/{id}/pages/{number}: the page viewer of a citation (ADR-0035). */
public interface DocumentPageService {

    /**
     * The stored text of one page or section of a document the caller reads (ADR-0028 principals). verso-admin reads
     * no more than its principals allow: managing a collection is not reading it. Another or a missing document is
     * DOCUMENT_NOT_FOUND; a number the readable document does not have is DOCUMENT_PAGE_NOT_FOUND.
     */
    DocumentPageResponse page(Caller caller, UUID documentId, int number);
}
