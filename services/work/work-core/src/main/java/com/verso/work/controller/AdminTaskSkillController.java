package com.verso.work.controller;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.work.api.dto.AdminSkillResponse;
import com.verso.work.api.dto.CustomSkillRequest;
import com.verso.work.api.dto.CustomSkillResponse;
import com.verso.work.api.dto.SkillEnabledRequest;
import com.verso.work.api.dto.SkillValidationResponse;
import com.verso.work.service.SkillAdminService;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * /v1/admin skill endpoints (ADR-0057): verso-admin only, declared once on the class (ADR-0019) so a new method cannot
 * forget it; a 403 is recorded as ACCESS_DENIED with the endpoint word ADMIN_TASK_SKILL (ADR-0026). The installation's
 * switch per skill and the customer-authored skills of the consulting team. Every response is no-store.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin")
@PreAuthorize(VersoRoles.HAS_ADMIN)
public class AdminTaskSkillController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final SkillAdminService service;

    public AdminTaskSkillController(SkillAdminService service) {
        this.service = service;
    }

    @GetMapping("/task-skills")
    public ResponseEntity<List<AdminSkillResponse>> skills() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.list());
    }

    @PutMapping(path = "/task-skills/{skillId}/enabled", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<AdminSkillResponse> enabled(@PathVariable("skillId") String skillId,
                                                      @Valid @RequestBody SkillEnabledRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.setEnabled(skillId, request.enabled()));
    }

    @GetMapping("/custom-skills")
    public ResponseEntity<List<CustomSkillResponse>> customSkills() {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.customSkills());
    }

    @GetMapping("/custom-skills/{id}")
    public ResponseEntity<CustomSkillResponse> customSkill(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.get(id));
    }

    /** The JSON editor's check: the same rules as a save, nothing stored. */
    @PostMapping(path = "/custom-skills/validate", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SkillValidationResponse> validate(@Valid @RequestBody CustomSkillRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.validate(request.definition()));
    }

    /** 201: a DRAFT at version 1; users see it only after approval. */
    @PostMapping(path = "/custom-skills", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomSkillResponse> create(@Valid @RequestBody CustomSkillRequest request) {
        CustomSkillResponse created = service.create(request.definition());
        return ResponseEntity.created(URI.create("/v1/admin/custom-skills/" + created.id())).cacheControl(NO_STORE)
                .body(created);
    }

    /** A new version; the skill is a DRAFT again (hidden from users) until approved. */
    @PutMapping(path = "/custom-skills/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CustomSkillResponse> update(@PathVariable("id") UUID id,
                                                      @Valid @RequestBody CustomSkillRequest request) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.update(id, request.definition()));
    }

    @PostMapping("/custom-skills/{id}/approve")
    public ResponseEntity<CustomSkillResponse> approve(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.approve(id));
    }

    @PostMapping("/custom-skills/{id}/archive")
    public ResponseEntity<CustomSkillResponse> archive(@PathVariable("id") UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(service.archive(id));
    }
}
