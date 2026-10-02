package com.verso.document.service;

import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.web.AccountId;
import java.util.UUID;

/** Use cases of /v1/documents. The account always comes from the validated token (@CurrentAccount), never the request. */
public interface DocumentService {

    /** Stores the PDF as PENDING; with an idempotency key a repeated upload returns the first document. */
    DocumentResponse upload(AccountId account, String fileName, byte[] content, UUID idempotencyKey);

    PageResponse<DocumentResponse> list(AccountId account, int page, int size);

    DocumentResponse get(AccountId account, UUID documentId);

    void delete(AccountId account, UUID documentId);
}
