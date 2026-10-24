package com.verso.document.service.impl;

import com.verso.document.api.dto.DocumentPageResponse;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import com.verso.document.repository.DocumentPageRepository;
import com.verso.document.repository.DocumentPageRepository.PageRow;
import com.verso.document.service.Caller;
import com.verso.document.service.DocumentPageService;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * One page of a readable document (ADR-0035). A read: no audit event (ADR-0026 records changes, not reads) and no log
 * line of its own; the page text is never logged (llm-rules 2.1). One statement, no transaction around it.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentPageServiceImpl implements DocumentPageService {

    private final DocumentPageRepository pages;

    public DocumentPageServiceImpl(DocumentPageRepository pages) {
        this.pages = pages;
    }

    @Override
    public DocumentPageResponse page(Caller caller, UUID documentId, int number) {
        PageRow row = pages.find(caller.principals(), documentId, number, DocumentPageResponse.MAX_TEXT_CHARS)
                .orElseThrow(() -> new DocumentServiceException(DocumentErrorCode.DOCUMENT_NOT_FOUND));
        if (row.text() == null) throw new DocumentServiceException(DocumentErrorCode.DOCUMENT_PAGE_NOT_FOUND);
        String text = row.text();
        // PostgreSQL's left() counts code points, so the cap does too (never half a surrogate pair at the cut).
        boolean truncated = text.codePointCount(0, text.length()) > DocumentPageResponse.MAX_TEXT_CHARS;
        if (truncated) text = text.substring(0, text.offsetByCodePoints(0, DocumentPageResponse.MAX_TEXT_CHARS));
        return new DocumentPageResponse(documentId, number, row.format().unit(), row.pageCount(), text, truncated);
    }
}
