package com.verso.connector.controller;

import com.verso.connector.api.dto.ConnectorItemResponse;
import com.verso.connector.api.dto.ConnectorPageResponse;
import com.verso.connector.api.dto.ConnectorRequest;
import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.dto.SyncRequest;
import com.verso.connector.api.dto.SyncRunResponse;
import com.verso.connector.api.enums.ItemState;
import com.verso.connector.service.ConnectorAdminService;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/connectors (ADR-0038): verso-admin only, declared once on the class (ADR-0019) so a new method cannot
 * forget it; a 403 is recorded as ACCESS_DENIED with the endpoint word ADMIN_CONNECTOR (ADR-0026). No response carries
 * a password: the request never has one and the row never stores one. Every response is no-store.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/connectors")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class AdminConnectorController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final ConnectorAdminService service;

    public AdminConnectorController(ConnectorAdminService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorResponse> create(@CurrentAccount AccountId account,
                                                    @Valid @RequestBody ConnectorRequest request) {
        ConnectorResponse created = service.create(account.value(), request);
        return ResponseEntity.created(URI.create("/v1/admin/connectors/" + created.id())).cacheControl(NO_STORE)
                .body(created);
    }

    @GetMapping
    public ResponseEntity<ConnectorPageResponse<ConnectorResponse>> list(
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.list(page, size));
    }

    @GetMapping("/{connectorId}")
    public ResponseEntity<ConnectorResponse> get(@PathVariable("connectorId") UUID connectorId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.get(connectorId));
    }

    /** Replaces the settings; the target collection cannot change (409). The next sync runs at once. */
    @PutMapping(path = "/{connectorId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ConnectorResponse> update(@PathVariable("connectorId") UUID connectorId,
                                                    @Valid @RequestBody ConnectorRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.update(connectorId, request));
    }

    /**
     * 202: the connector stops at once and its documents are deleted in the background with all their derived data
     * (ADR-0034); then it disappears from the list. Repeating the call is harmless.
     */
    @DeleteMapping("/{connectorId}")
    public ResponseEntity<Void> delete(@PathVariable("connectorId") UUID connectorId) {
        service.delete(connectorId);
        return ResponseEntity.accepted().cacheControl(NO_STORE).build();
    }

    /** 202: due now; a running sync finishes first and then runs again. The body is optional. */
    @PostMapping("/{connectorId}/sync")
    public ResponseEntity<Void> sync(@PathVariable("connectorId") UUID connectorId,
                                     @RequestBody(required = false) SyncRequest request) {
        service.requestSync(connectorId, request != null && Boolean.TRUE.equals(request.confirmMassDeletion()));
        return ResponseEntity.accepted().cacheControl(NO_STORE).build();
    }

    @GetMapping("/{connectorId}/runs")
    public ResponseEntity<ConnectorPageResponse<SyncRunResponse>> runs(
            @PathVariable("connectorId") UUID connectorId,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.runs(connectorId, page, size));
    }

    /** The tracked files, optionally one state (FAILED: what to fix at the source). Paths appear here only. */
    @GetMapping("/{connectorId}/items")
    public ResponseEntity<ConnectorPageResponse<ConnectorItemResponse>> items(
            @PathVariable("connectorId") UUID connectorId,
            @RequestParam(name = "state", required = false) ItemState state,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.items(connectorId, state, page, size));
    }
}
