package com.verso.document.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.service.DocumentService;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /v1/documents (ADR-0004, ADR-0011). Binding only: the account comes from the token, everything else is decided in
 * {@link DocumentService}. Responses carry the caller's file names: {@code Cache-Control: private, no-store}.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@Validated
@RequestMapping("/v1/documents")
public class DocumentController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    /** 201 with Location; the document is PENDING until the worker has processed it. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ResponseEntity<DocumentResponse> upload(@CurrentAccount AccountId account,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestHeader(name = "X-Idempotency-Key", required = false)
                                                   UUID idempotencyKey) throws IOException {
        DocumentResponse created = service.upload(account, file.getOriginalFilename(), file.getBytes(), idempotencyKey);
        return ResponseEntity.created(URI.create("/v1/documents/" + created.id())).cacheControl(PRIVATE).body(created);
    }

    @GetMapping
    public ResponseEntity<PageResponse<DocumentResponse>> list(@CurrentAccount AccountId account,
                                                               @RequestParam(name = "page", defaultValue = "0")
                                                               @Min(0) @Max(100_000) int page,
                                                               @RequestParam(name = "size", defaultValue = "20")
                                                               @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.list(account, page, size));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(@CurrentAccount AccountId account,
                                                @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.get(account, documentId));
    }

    /** Deletes the document with its pages, chunks and vectors (KVKK erasure, llm-rules 4.2). */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountId account, @PathVariable("documentId") UUID documentId) {
        service.delete(account, documentId);
        return ResponseEntity.noContent().build();
    }
}
