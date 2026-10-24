package com.verso.document.service.impl;

import com.verso.document.api.dto.DocumentChunkResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.enums.SourceUnit;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.DocumentChunkRepository;
import com.verso.document.repository.DocumentChunkRepository.ChunkPage;
import com.verso.document.service.Caller;
import com.verso.document.service.DocumentChunkService;
import com.verso.platform.security.authority.VersoRoles;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;

/**
 * The chunk inspector (ADR-0036). A read: no audit event (ADR-0026 records changes, not reads) and no log line of its
 * own; chunk text is never logged (llm-rules 2.1). One statement, no transaction around it. The admin rule is repeated
 * here (ADR-0019, defence in depth); the reader rule is the statement's.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentChunkServiceImpl implements DocumentChunkService {

    private final DocumentChunkRepository chunks;

    public DocumentChunkServiceImpl(DocumentChunkRepository chunks) {
        this.chunks = chunks;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public PageResponse<DocumentChunkResponse> chunks(Caller caller, UUID documentId, int page, int size) {
        ChunkPage found = chunks.find(caller.principals(), documentId, page, size, DocumentChunkResponse.MAX_TEXT_CHARS)
                .orElseThrow(() -> new DocumentServiceException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        SourceUnit unit = found.format().unit();
        return PageResponse.of(found.chunks().stream()
                .map(c -> new DocumentChunkResponse(c.index(), c.number(), unit, c.chars(), c.text(),
                        c.chars() > DocumentChunkResponse.MAX_TEXT_CHARS))
                .toList(), page, size, found.total());
    }
}
