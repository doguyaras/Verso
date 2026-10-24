package com.verso.document.controller;

import com.verso.document.api.dto.CollectionGrantRequest;
import com.verso.document.api.dto.CollectionGrantResponse;
import com.verso.document.api.dto.CollectionRequest;
import com.verso.document.api.dto.CollectionResponse;
import com.verso.document.service.CollectionService;
import com.verso.document.service.CollectionService.GrantResult;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Managing /v1/collections (ADR-0028): verso-admin only, declared once on the class (ADR-0019) so a new method cannot
 * forget it; a 403 is recorded as ACCESS_DENIED with the endpoint word COLLECTION_ADMIN (ADR-0026). Grants name IdP
 * groups and subs: they are returned only here and never logged.
 */
@RestController
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/collections")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class CollectionAdminController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();

    private final CollectionService service;
    private final CallerResolver callers;

    public CollectionAdminController(CollectionService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CollectionResponse> create(@CurrentAccount AccountId account,
                                                     @Valid @RequestBody CollectionRequest request) {
        CollectionResponse created = service.create(callers.resolve(account), request);
        return ResponseEntity.created(URI.create("/v1/collections/" + created.id())).cacheControl(PRIVATE).body(created);
    }

    @PutMapping(path = "/{collectionId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CollectionResponse> update(@CurrentAccount AccountId account,
                                                     @PathVariable("collectionId") UUID collectionId,
                                                     @Valid @RequestBody CollectionRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE)
                .body(service.update(callers.resolve(account), collectionId, request));
    }

    /** 409 COLLECTION_NOT_EMPTY while documents remain: they are moved or deleted explicitly, never implicitly. */
    @DeleteMapping("/{collectionId}")
    public ResponseEntity<Void> delete(@CurrentAccount AccountId account, @PathVariable("collectionId") UUID collectionId) {
        service.delete(callers.resolve(account), collectionId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{collectionId}/grants")
    public ResponseEntity<List<CollectionGrantResponse>> grants(@CurrentAccount AccountId account,
                                                                @PathVariable("collectionId") UUID collectionId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(service.grants(callers.resolve(account), collectionId));
    }

    /** 201 for a new grant, 200 with the existing one when it is already there (idempotent). */
    @PostMapping(path = "/{collectionId}/grants", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CollectionGrantResponse> grant(@CurrentAccount AccountId account,
                                                         @PathVariable("collectionId") UUID collectionId,
                                                         @Valid @RequestBody CollectionGrantRequest request) {
        GrantResult result = service.grant(callers.resolve(account), collectionId, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).cacheControl(PRIVATE)
                .body(result.grant());
    }

    @DeleteMapping("/{collectionId}/grants/{grantId}")
    public ResponseEntity<Void> revoke(@CurrentAccount AccountId account, @PathVariable("collectionId") UUID collectionId,
                                       @PathVariable("grantId") UUID grantId) {
        service.revoke(callers.resolve(account), collectionId, grantId);
        return ResponseEntity.noContent().build();
    }
}
