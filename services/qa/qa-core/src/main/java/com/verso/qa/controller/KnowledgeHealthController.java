package com.verso.qa.controller;

import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.Reader;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.HookNotificationResponse;
import com.verso.qa.api.dto.HookRuleResponse;
import com.verso.qa.api.dto.HookRuleUpdateRequest;
import com.verso.qa.api.dto.KnowledgeHealthFormat;
import com.verso.qa.api.dto.KnowledgeHealthReport;
import com.verso.qa.api.dto.KnowledgeHealthRunResponse;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.KnowledgeDiagnostics;
import com.verso.qa.service.KnowledgeHealth;
import com.verso.qa.service.KnowledgeHookAdmin;
import com.verso.qa.service.QaAudit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The knowledge-health report, the diagnostics runs, the hook rules and the hook notifications (ADR-0052):
 * {@code verso-admin} only (ADR-0019), declared once on the class; every other caller gets 403 ACCESS_DENIED. The admin
 * and the admin's groups come from the validated token: what the report names is filtered with them (ADR-0028).
 * Responses are never cached; an export is an attachment.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class KnowledgeHealthController {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHealthController.class);
    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();
    private static final MediaType MARKDOWN = new MediaType("text", "markdown", StandardCharsets.UTF_8);

    private final KnowledgeHealth health;
    private final KnowledgeDiagnostics diagnostics;
    private final KnowledgeHookAdmin hooks;
    private final QaAudit audit;
    private final CallerGroups groups;

    public KnowledgeHealthController(KnowledgeHealth health, KnowledgeDiagnostics diagnostics, KnowledgeHookAdmin hooks,
                                     QaAudit audit, CallerGroups groups) {
        this.health = health;
        this.diagnostics = diagnostics;
        this.hooks = hooks;
        this.audit = audit;
        this.groups = groups;
    }

    /** The report from the latest diagnostics run whose window ends in from..to (inclusive days). */
    @GetMapping("/knowledge-health")
    public ResponseEntity<KnowledgeHealthReport> report(
            @CurrentAccount AccountId admin,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        KnowledgeHealthReport report = health.report(readerOf(admin), from, to);
        audit.knowledgeHealthViewed("VIEW");
        return ResponseEntity.ok().cacheControl(PRIVATE).body(report);
    }

    /** The same report as a file for the consulting team: Markdown (default) or JSON. */
    @GetMapping("/knowledge-health/export")
    public ResponseEntity<byte[]> export(
            @CurrentAccount AccountId admin,
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(name = "format", defaultValue = "MARKDOWN") KnowledgeHealthFormat format) {
        byte[] body = health.export(readerOf(admin), from, to, format);
        boolean markdown = format == KnowledgeHealthFormat.MARKDOWN;
        audit.knowledgeHealthViewed(format.name());
        // The name holds the dates only (it ends up in download lists and synced folders).
        String name = "bilgi-sagligi-" + from + "-" + to + (markdown ? ".md" : ".json");
        return ResponseEntity.ok().cacheControl(PRIVATE)
                .contentType(markdown ? MARKDOWN : MediaType.APPLICATION_JSON)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(body);
    }

    @GetMapping("/knowledge-health/runs")
    public ResponseEntity<PageResponse<KnowledgeHealthRunResponse>> runs(
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(diagnostics.runs(page, size));
    }

    /** Runs the diagnostics now (synchronous, bounded by its limits); 409 while another run holds the lock. */
    @PostMapping("/knowledge-health/runs")
    public ResponseEntity<KnowledgeHealthRunResponse> run() {
        KnowledgeHealthRunResponse run = diagnostics.run("MANUAL").orElseThrow(() -> {
            log.warn("Knowledge diagnostics rejected: code={} reason=ALREADY_RUNNING",
                    QaErrorCode.KNOWLEDGE_HEALTH_RUNNING.getCode());
            return new QaServiceException(QaErrorCode.KNOWLEDGE_HEALTH_RUNNING, "KNOWLEDGE_HEALTH_RUNNING");
        });
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(PRIVATE).body(run);
    }

    @GetMapping("/knowledge-hooks")
    public ResponseEntity<List<HookRuleResponse>> rules() {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(hooks.rules());
    }

    /** Switches one rule on or off; the rule set and each rule's action are fixed. */
    @PutMapping("/knowledge-hooks/{key}")
    public ResponseEntity<HookRuleResponse> update(
            @PathVariable("key") @Pattern(regexp = "[A-Z][A-Z0-9_]{2,39}") String key,
            @Valid @RequestBody HookRuleUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(hooks.setEnabled(key, request.enabled()));
    }

    /** The hook notifications, newest first; {@code unread=true} for the bell. */
    @GetMapping("/notifications")
    public ResponseEntity<PageResponse<HookNotificationResponse>> notifications(
            @RequestParam(name = "unread", defaultValue = "false") boolean unread,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(hooks.notifications(unread, page, size));
    }

    @PostMapping("/notifications/{id}/read")
    public ResponseEntity<HookNotificationResponse> read(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(hooks.markRead(id));
    }

    private Reader readerOf(AccountId admin) {
        // The admin's sub and token groups (ADR-0028): names and texts only of what this admin may read.
        return new Reader(admin.value(), groups.current());
    }
}
