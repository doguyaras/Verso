package com.verso.document.controller;

import com.verso.document.api.dto.DocumentChunkResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.service.DocumentChunkService;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /v1/admin/documents/{id}/chunks (ADR-0036): the chunk inspector, verso-admin only (ADR-0019; a denial is an
 * audit row, ADR-0026). Binding only; the caller and its principals come from the token ({@link CallerResolver}). The
 * body is document text: {@code Cache-Control: private, no-store}.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/documents")
public class AdminDocumentChunkController {

    private final DocumentChunkService service;
    private final CallerResolver callers;

    public AdminDocumentChunkController(DocumentChunkService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    /** Chunks in their order; {@code size} 1-100 (default 50), {@code page} 0-100000. */
    @GetMapping("/{documentId}/chunks")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<PageResponse<DocumentChunkResponse>> chunks(@CurrentAccount AccountId account,
                                                                      @PathVariable("documentId") UUID documentId,
                                                                      @RequestParam(name = "page", defaultValue = "0")
                                                                      @Min(0) @Max(100_000) int page,
                                                                      @RequestParam(name = "size", defaultValue = "50")
                                                                      @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(service.chunks(callers.resolve(account), documentId, page, size));
    }
}
