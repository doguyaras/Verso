package com.verso.document.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.dto.DocumentResponse;
import com.verso.document.api.dto.MoveDocumentRequest;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.service.DocumentService;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /v1/documents (ADR-0004, ADR-0011, ADR-0028). Binding only: the caller (account, groups, admin role) comes from the
 * token, everything else is decided in {@link DocumentService}. Responses carry file and collection names:
 * {@code Cache-Control: private, no-store}.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/documents")
public class DocumentController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final DocumentService service;
    private final CallerResolver callers;

    public DocumentController(DocumentService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    /** 201 with Location; the document is PENDING in the caller's personal space until the worker has processed it. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> upload(@CurrentAccount AccountId account,
                                                   @RequestPart("file") MultipartFile file,
                                                   @RequestHeader(name = "X-Idempotency-Key", required = false)
                                                   UUID idempotencyKey) throws IOException {
        return created(service.upload(callers.resolve(account), null, file.getOriginalFilename(), file.getBytes(),
                idempotencyKey));
    }

    /** The same upload into a shared collection: verso-admin only (ADR-0028); its readers are the collection's grants. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, params = "collectionId")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<DocumentResponse> uploadToCollection(@CurrentAccount AccountId account,
                                                               @RequestParam("collectionId") UUID collectionId,
                                                               @RequestPart("file") MultipartFile file,
                                                               @RequestHeader(name = "X-Idempotency-Key", required = false)
                                                               UUID idempotencyKey) throws IOException {
        return created(service.upload(callers.resolve(account), collectionId, file.getOriginalFilename(), file.getBytes(),
                idempotencyKey));
    }

    /** Every document the caller reads (personal and shared), or one collection's with {@code collectionId}. */
    @GetMapping
    public ResponseEntity<PageResponse<DocumentResponse>> list(@CurrentAccount AccountId account,
                                                               @RequestParam(name = "collectionId", required = false)
                                                               UUID collectionId,
                                                               @RequestParam(name = "page", defaultValue = "0")
                                                               @Min(0) @Max(100_000) int page,
                                                               @RequestParam(name = "size", defaultValue = "20")
                                                               @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE)
                .body(service.list(callers.resolve(account), collectionId, page, size));
    }

    @GetMapping("/{documentId}")
    public ResponseEntity<DocumentResponse> get(@CurrentAccount AccountId account,
                                                @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.get(callers.resolve(account), documentId));
    }

    /** Deletes the document with its pages, chunks and vectors (KVKK erasure, llm-rules 4.2). */
    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountId account, @PathVariable("documentId") UUID documentId) {
        service.delete(callers.resolve(account), documentId);
        return ResponseEntity.noContent().build();
    }

    /** verso-admin: moves a document to a collection, or ({@code collectionId: null}) to its uploader's personal space. */
    @PutMapping(path = "/{documentId}/collection", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<DocumentResponse> move(@CurrentAccount AccountId account,
                                                 @PathVariable("documentId") UUID documentId,
                                                 @Valid @RequestBody MoveDocumentRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE)
                .body(service.move(callers.resolve(account), documentId, request.collectionId()));
    }

    private static ResponseEntity<DocumentResponse> created(DocumentResponse created) {
        return ResponseEntity.created(URI.create("/v1/documents/" + created.id())).cacheControl(PRIVATE).body(created);
    }
}
