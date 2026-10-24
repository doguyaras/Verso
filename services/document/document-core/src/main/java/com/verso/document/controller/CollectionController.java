package com.verso.document.controller;

import com.verso.document.api.dto.CollectionResponse;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.service.CollectionService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading /v1/collections (ADR-0028): every authenticated caller sees the collections its principals are granted;
 * verso-admin sees all of them, each marked {@code readable} or not. A collection outside that set answers 404 like a
 * missing one (IDOR). Management is {@link CollectionAdminController}.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/collections")
public class CollectionController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final CollectionService service;
    private final CallerResolver callers;

    public CollectionController(CollectionService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @GetMapping
    public ResponseEntity<PageResponse<CollectionResponse>> list(@CurrentAccount AccountId account,
                                                                 @RequestParam(name = "page", defaultValue = "0")
                                                                 @Min(0) @Max(100_000) int page,
                                                                 @RequestParam(name = "size", defaultValue = "50")
                                                                 @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.list(callers.resolve(account), page, size));
    }

    @GetMapping("/{collectionId}")
    public ResponseEntity<CollectionResponse> get(@CurrentAccount AccountId account,
                                                  @PathVariable("collectionId") UUID collectionId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.get(callers.resolve(account), collectionId));
    }
}
