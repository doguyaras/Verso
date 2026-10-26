package com.verso.document.controller;

import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemModelStats;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.service.SystemModelService;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The light system model (ADR-0054). {@code GET /v1/system-model/lookup}: any signed-in caller looks up a code, an
 * abbreviation or a system name in the documents it may read. {@code /v1/admin/system-map/**}: the panel's "Sistem
 * haritası", verso-admin only (ADR-0019; every other caller gets 403). Both read with the caller's own principals
 * (ADR-0028): an admin, too, sees only what it may read. Binding only; the caller comes from the token
 * ({@link CallerResolver}). The bodies are document text: {@code Cache-Control: private, no-store}; the query is never
 * logged.
 */
@RestController
@Validated
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemModelController {

    private final SystemModelService service;
    private final CallerResolver callers;

    public SystemModelController(SystemModelService service, CallerResolver callers) {
        this.service = service;
        this.callers = callers;
    }

    /** Entities named by {@code q} (1-200 characters), each with definitions, relations and up to five sources. */
    @GetMapping("/v1/system-model/lookup")
    public ResponseEntity<List<SystemEntityDetail>> lookup(@CurrentAccount AccountId account,
                                                           @RequestParam(name = "q", required = false) String q) {
        return noStore(service.lookup(callers.resolve(account), q));
    }

    /** Browsing: entities of a type (all but steps when absent) whose key starts with {@code prefix}. */
    @GetMapping("/v1/admin/system-map/entities")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<List<SystemEntitySummary>> entities(
            @CurrentAccount AccountId account,
            @RequestParam(name = "type", required = false) SystemEntityType type,
            @RequestParam(name = "prefix", required = false) @Size(max = 200) String prefix,
            @RequestParam(name = "limit", defaultValue = "100") @Min(1) @Max(200) int limit) {
        return noStore(service.browse(callers.resolve(account), type, prefix, limit));
    }

    /** One entity: definitions, relations (system to screens and codes, process to steps and owner) and sources. */
    @GetMapping("/v1/admin/system-map/entity")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<SystemEntityDetail> entity(@CurrentAccount AccountId account,
                                                     @RequestParam("type") SystemEntityType type,
                                                     @RequestParam("key") @Size(min = 1, max = 200) String key) {
        return noStore(service.entity(callers.resolve(account), type, key));
    }

    @GetMapping("/v1/admin/system-map/stats")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<SystemModelStats> stats(@CurrentAccount AccountId account) {
        return noStore(service.stats(callers.resolve(account)));
    }

    private static <T> ResponseEntity<T> noStore(T body) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(body);
    }
}
