package com.verso.audit.controller;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditPageResponse;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.audit.service.AuditQueryService;
import com.verso.audit.service.AuditQueryService.ExportPlan;
import com.verso.platform.security.authority.VersoRoles;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/audit (ADR-0026): the audit log for {@code verso-admin} only (ADR-0019); every other caller gets 403
 * ACCESS_DENIED (and the denial is itself audited). Rows hold ids and fixed words, never content, yet they say who did
 * what: {@code Cache-Control: no-store} on both uses. The export is written from the request thread in keyset batches
 * (no async dispatch, so the caller's security context stays), capped at {@code verso.audit.export-max-rows}.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/audit")
public class AdminAuditController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final AuditQueryService audit;
    private final ApplicationEventPublisher events;

    public AdminAuditController(AuditQueryService audit, ApplicationEventPublisher events) {
        this.audit = audit;
        this.events = events;
    }

    /** Newest first. {@code from} and {@code to} are inclusive calendar days (ISO, e.g. 2026-10-01); at most 200 per page. */
    @GetMapping
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<AuditPageResponse> list(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "action", required = false) AuditAction action,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(10_000) int page,
            @RequestParam(name = "size", defaultValue = "50") @Min(1) @Max(200) int size) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(audit.page(from, to, action, page, size));
    }

    /**
     * The same window as CSV, oldest first. The response says up front whether the cap cuts it
     * ({@code X-Audit-Export-Truncated}); narrow the window to get the rest. The export is audited before it starts.
     */
    @GetMapping("/export")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public void export(@RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                       @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                       HttpServletResponse response) throws IOException {
        ExportPlan plan = audit.planExport(from, to);
        events.publishEvent(AuditEvent.success(AuditAction.AUDIT_EXPORTED, AuditTargetType.AUDIT_LOG));
        response.setStatus(200);
        response.setContentType("text/csv");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE.getHeaderValue());
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"audit-" + from + "_" + to + ".csv\"");
        response.setHeader("X-Audit-Export-Truncated", Boolean.toString(plan.truncated()));
        response.setHeader("X-Audit-Export-Max-Rows", Integer.toString(plan.maxRows()));
        try (Writer out = new OutputStreamWriter(response.getOutputStream(), StandardCharsets.UTF_8)) {
            audit.writeExport(plan, out);
        }
    }
}
