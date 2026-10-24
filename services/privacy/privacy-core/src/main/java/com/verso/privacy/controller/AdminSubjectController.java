package com.verso.privacy.controller;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.privacy.api.dto.SubjectDeletionRequest;
import com.verso.privacy.api.dto.SubjectDeletionResponse;
import com.verso.privacy.api.dto.SubjectSummaryResponse;
import com.verso.privacy.service.SubjectRequestService;
import com.verso.privacy.service.SubjectRequestService.ExportPlan;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import java.io.IOException;
import java.io.OutputStream;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin/subjects/{sub} (ADR-0034): KVKK md. 11 requests about one token subject, for {@code verso-admin} only
 * (ADR-0019; every other caller gets 403 and the denial is audited, ADR-0026). The subject is a request target, never
 * the caller's identity: the caller is the admin of the validated token. Every response carries personal data:
 * {@code Cache-Control: no-store}. The sub is checked against the token subject's shape before any query.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/subjects")
public class AdminSubjectController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final SubjectRequestService subjects;

    public AdminSubjectController(SubjectRequestService subjects) {
        this.subjects = subjects;
    }

    /** What Verso holds about the subject: counts, document metadata, collections naming the subject, audit row count. */
    @GetMapping("/{sub}")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<SubjectSummaryResponse> summary(@PathVariable("sub") String sub) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(subjects.summary(sub));
    }

    /**
     * Everything as one JSON document (access request), written from the request thread in bounded batches (the
     * caller's security context stays). {@code X-Subject-Export-Truncated} says up front whether a cap cuts it.
     */
    @GetMapping("/{sub}/export")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public void export(@PathVariable("sub") String sub, HttpServletResponse response) throws IOException {
        ExportPlan plan = subjects.planExport(sub);
        response.setStatus(200);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader(HttpHeaders.CACHE_CONTROL, NO_STORE.getHeaderValue());
        // A fixed file name: the sub stays out of headers that proxies and browsers may keep.
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"verso-kvkk-export.json\"");
        response.setHeader("X-Subject-Export-Truncated", Boolean.toString(plan.truncated()));
        try (OutputStream out = response.getOutputStream()) {
            subjects.writeExport(plan, out);
        }
    }

    /**
     * Erasure request: history and personal documents go, collection documents and audit rows are reported and kept.
     * The body must repeat the sub ({@code {"confirm": "<sub>"}}); a repeat of the request is safe and finishes an
     * interrupted erasure.
     */
    @DeleteMapping("/{sub}")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<SubjectDeletionResponse> delete(@PathVariable("sub") String sub,
                                                          @Valid @RequestBody SubjectDeletionRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(subjects.delete(sub, request));
    }
}
