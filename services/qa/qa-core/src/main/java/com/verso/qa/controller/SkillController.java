package com.verso.qa.controller;

import com.verso.document.api.dto.PageResponse;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.SkillDraftRequest;
import com.verso.qa.api.dto.SkillResponse;
import com.verso.qa.api.dto.SkillState;
import com.verso.qa.api.dto.SkillSuggestion;
import com.verso.qa.api.dto.SkillSummary;
import com.verso.qa.api.dto.SkillUpdateRequest;
import com.verso.qa.api.dto.SkillVersionDiff;
import com.verso.qa.service.SkillService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
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

/**
 * /v1/admin/skills (ADR-0053): the procedures of the learning layer, {@code verso-admin} only (ADR-0019); every other
 * caller gets 403 ACCESS_DENIED. The admin comes from the validated token. Responses are never cached; the export is
 * an attachment (a zip with {@code <name>/SKILL.md}).
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/skills")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class SkillController {

    private static final CacheControl PRIVATE = CacheControl.noStore().cachePrivate();
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final SkillService skills;

    public SkillController(SkillService skills) {
        this.skills = skills;
    }

    /** A draft from a diagnostics cluster or from chosen verified cards and the caller's own sourced answers. */
    @PostMapping
    public ResponseEntity<SkillResponse> draft(@CurrentAccount AccountId admin,
                                               @Valid @RequestBody SkillDraftRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(PRIVATE).body(skills.draft(admin, request));
    }

    /** Newest change first; {@code state} filters (DRAFT, APPROVED, STALE, ARCHIVED). */
    @GetMapping
    public ResponseEntity<PageResponse<SkillSummary>> list(
            @CurrentAccount AccountId admin,
            @RequestParam(name = "state", required = false) SkillState state,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) @Max(100_000) int page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) int size) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.list(admin, state, page, size));
    }

    /** The latest diagnostics run's clusters with verified cards around them, likely procedures first. */
    @GetMapping("/suggestions")
    public ResponseEntity<List<SkillSuggestion>> suggestions(@CurrentAccount AccountId admin) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.suggestions(admin));
    }

    @GetMapping("/{skillId}")
    public ResponseEntity<SkillResponse> get(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.get(admin, skillId));
    }

    /** A new version (reworded, reordered or fewer steps; new triggers and golden cases); back to DRAFT. */
    @PutMapping("/{skillId}")
    public ResponseEntity<SkillResponse> update(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId,
                                                @Valid @RequestBody SkillUpdateRequest request) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.update(admin, skillId, request));
    }

    @PostMapping("/{skillId}/approve")
    public ResponseEntity<SkillResponse> approve(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.approve(admin, skillId));
    }

    @PostMapping("/{skillId}/archive")
    public ResponseEntity<SkillResponse> archive(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.archive(admin, skillId));
    }

    /** What changed between two versions of the skill. */
    @GetMapping("/{skillId}/diff")
    public ResponseEntity<SkillVersionDiff> diff(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId,
                                                 @RequestParam("from") @Min(1) @Max(100_000) int from,
                                                 @RequestParam("to") @Min(1) @Max(100_000) int to) {
        return ResponseEntity.ok().cacheControl(PRIVATE).body(skills.diff(admin, skillId, from, to));
    }

    /** The approved skill as a SKILL.md bundle (zip); the file name is the skill's name. */
    @GetMapping("/{skillId}/export")
    public ResponseEntity<byte[]> export(@CurrentAccount AccountId admin, @PathVariable("skillId") UUID skillId) {
        SkillService.Export export = skills.export(admin, skillId);
        return ResponseEntity.ok().cacheControl(PRIVATE).contentType(ZIP)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(export.fileName()).build().toString())
                .body(export.zip());
    }
}
