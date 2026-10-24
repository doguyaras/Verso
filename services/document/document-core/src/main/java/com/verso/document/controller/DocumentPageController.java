package com.verso.document.controller;

import com.verso.document.api.dto.DocumentPageResponse;
import com.verso.document.service.DocumentPageService;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/documents/{id}/pages/{number} (ADR-0035): the page viewer of a citation. Binding only; the caller comes from
 * the token ({@link CallerResolver}). The body is document text: {@code Cache-Control: private, no-store}.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/documents")
public class DocumentPageController {

    private final DocumentPageService service;
    private final CallerResolver callers;

    public DocumentPageController(DocumentPageService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    /** {@code number} counts pages of a PDF and sections of the other formats, like citations (ADR-0016). */
    @GetMapping("/{documentId}/pages/{number}")
    public ResponseEntity<DocumentPageResponse> page(@CurrentAccount AccountId account,
                                                     @PathVariable("documentId") UUID documentId,
                                                     @PathVariable("number") @Min(1) @Max(100_000) int number) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(service.page(callers.resolve(account), documentId, number));
    }
}
