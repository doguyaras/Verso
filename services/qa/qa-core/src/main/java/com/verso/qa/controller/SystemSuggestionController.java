package com.verso.qa.controller;

import com.verso.document.api.dto.Reader;
import com.verso.platform.security.authority.CallerGroups;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.SystemSuggestionResponse;
import com.verso.qa.api.dto.SystemSuggestionRunResponse;
import com.verso.qa.service.SystemModelSuggestions;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The review of suggested system-model relations (ADR-0054), verso-admin only (ADR-0019), declared once on the class.
 * The optional local-model pass runs on one document at the admin's request, only when switched on; the admin's own
 * principals decide which documents it may touch (ADR-0028). No request body: nothing optional to bind.
 */
@RestController
@Validated
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/system-map/suggestions")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class SystemSuggestionController {

    private final SystemModelSuggestions suggestions;
    private final CallerGroups groups;

    public SystemSuggestionController(SystemModelSuggestions suggestions, CallerGroups groups) {
        this.suggestions = suggestions;
        this.groups = groups;
    }

    /** Whether the pass can run here (the panel hides its button otherwise). */
    @GetMapping("/settings")
    public ResponseEntity<Map<String, Boolean>> settings() {
        return ResponseEntity.ok(Map.of("enabled", suggestions.enabled()));
    }

    /** Suggestions of one state (DRAFT by default) on documents the admin may read, newest first. */
    @GetMapping
    public ResponseEntity<List<SystemSuggestionResponse>> list(
            @CurrentAccount AccountId admin,
            @RequestParam(name = "state", defaultValue = "DRAFT") @Pattern(regexp = "DRAFT|ACCEPTED|REJECTED")
            String state) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(suggestions.list(reader(admin), state));
    }

    /** Runs the pass on one document; 409 SYSTEM_SUGGESTIONS_DISABLED when it is off. */
    @PostMapping("/documents/{documentId}")
    public ResponseEntity<SystemSuggestionRunResponse> run(@CurrentAccount AccountId admin,
                                                           @PathVariable("documentId") UUID documentId) {
        return ResponseEntity.ok(suggestions.run(reader(admin), documentId));
    }

    @PostMapping("/{id}/accept")
    public ResponseEntity<SystemSuggestionResponse> accept(@CurrentAccount AccountId admin, @PathVariable("id") UUID id) {
        return ResponseEntity.ok(suggestions.review(reader(admin), id, true));
    }

    @PostMapping("/{id}/reject")
    public ResponseEntity<SystemSuggestionResponse> reject(@CurrentAccount AccountId admin, @PathVariable("id") UUID id) {
        return ResponseEntity.ok(suggestions.review(reader(admin), id, false));
    }

    private Reader reader(AccountId admin) {
        return new Reader(admin.value(), groups.current());
    }
}
