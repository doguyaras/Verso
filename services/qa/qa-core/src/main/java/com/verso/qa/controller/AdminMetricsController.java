package com.verso.qa.controller;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.qa.api.dto.UsageMetricsResponse;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.UsageMetricsService;
import java.time.LocalDate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/metrics (ADR-0023): usage and quality of the installation for the value dashboard, {@code verso-admin}
 * only (ADR-0019); every other caller gets 403 ACCESS_DENIED. Aggregates, no account ids. Not cached anywhere.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/metrics")
public class AdminMetricsController {

    private final UsageMetricsService metrics;
    private final QaAudit audit;

    public AdminMetricsController(UsageMetricsService metrics, QaAudit audit) {
        this.metrics = metrics;
        this.audit = audit;
    }

    /** {@code from} and {@code to} are inclusive calendar days (ISO, e.g. 2026-10-01) in the metrics time zone. */
    @GetMapping
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<UsageMetricsResponse> metrics(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        UsageMetricsResponse response = metrics.metrics(from, to);
        audit.metricsViewed();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(response);
    }
}
