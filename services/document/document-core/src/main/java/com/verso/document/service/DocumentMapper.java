package com.verso.document.service;

import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.repository.DocumentRow;

/** Row → response (reference 4.1: mapping lives in the service layer, one method name). */
public final class DocumentMapper {

    private DocumentMapper() {}

    public static DocumentResponse toResponse(DocumentRow row) {
        return new DocumentResponse(row.id(), row.fileName(), row.format(), row.status(), row.sizeBytes(), row.pageCount(),
                row.chunkCount(), row.failureReason(), row.createdAt(), row.updatedAt());
    }
}
