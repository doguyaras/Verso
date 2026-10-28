package com.verso.work.service.impl;

import com.verso.audit.api.enums.AuditAction;
import com.verso.work.api.dto.AdminSkillResponse;
import com.verso.work.api.dto.CustomSkillResponse;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillValidationResponse;
import com.verso.work.api.enums.CustomSkillState;
import com.verso.work.api.enums.SkillOrigin;
import com.verso.work.domain.SkillRules;
import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import com.verso.work.outbox.WorkAudit;
import com.verso.work.repository.SkillCatalogRepository;
import com.verso.work.repository.SkillCatalogRepository.CustomRow;
import com.verso.work.repository.WorkDatabase;
import com.verso.work.service.BuiltInSkills;
import com.verso.work.service.SkillAdminService;
import com.verso.work.service.SkillCatalog;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

/**
 * The verso-admin side of the skill library (ADR-0057): the installation's switch per skill, and the customer-authored
 * skills (create as DRAFT, new version = DRAFT again, approve, archive). A definition is checked by the same rules as
 * the repository's skills; its id and slash command may not be a built-in skill's or another live custom skill's.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SkillAdminServiceImpl implements SkillAdminService {

    private final BuiltInSkills builtIn;
    private final SkillCatalog catalog;
    private final SkillCatalogRepository repository;
    private final WorkDatabase db;
    private final WorkAudit audit;
    private final JsonMapper json;

    public SkillAdminServiceImpl(BuiltInSkills builtIn, SkillCatalog catalog, SkillCatalogRepository repository,
                             WorkDatabase db, WorkAudit audit, JsonMapper json) {
        this.builtIn = builtIn;
        this.catalog = catalog;
        this.repository = repository;
        this.db = db;
        this.audit = audit;
        this.json = json;
    }

    public List<AdminSkillResponse> list() {
        Map<String, Boolean> switches = repository.switches();
        List<AdminSkillResponse> all = new ArrayList<>();
        for (SkillDefinition d : builtIn.all()) {
            all.add(new AdminSkillResponse(SkillCatalog.summary(d, SkillOrigin.BUILT_IN),
                    switches.getOrDefault(d.id(), true), null, null, null));
        }
        for (CustomRow row : repository.live()) {
            SkillDefinition d = catalog.definition(row);
            CustomSkillState state = CustomSkillState.valueOf(row.state());
            all.add(new AdminSkillResponse(SkillCatalog.summary(d, SkillOrigin.CUSTOM),
                    state == CustomSkillState.APPROVED && switches.getOrDefault(row.skillId(), true), row.id(), state,
                    row.version()));
        }
        return all;
    }

    public AdminSkillResponse setEnabled(String skillId, boolean enabled) {
        boolean known = skillId != null && SkillRules.ID.matcher(skillId).matches()
                && (builtIn.contains(skillId) || repository.live().stream().anyMatch(r -> r.skillId().equals(skillId)));
        if (!known) throw new WorkServiceException(WorkErrorCode.SKILL_NOT_FOUND, "UNKNOWN");
        db.transactionally(() -> {
            repository.setSwitch(skillId, enabled);
            audit.skill(AuditAction.TASK_SKILL_TOGGLED, WorkAudit.skillTarget(skillId), enabled ? "ENABLED" : "DISABLED");
            return null;
        });
        return list().stream().filter(s -> s.skill().id().equals(skillId)).findFirst().orElseThrow();
    }

    public SkillValidationResponse validate(SkillDefinition definition) {
        List<String> errors = new ArrayList<>(SkillRules.validate(definition, false));
        return new SkillValidationResponse(errors.isEmpty(), List.copyOf(errors));
    }

    public CustomSkillResponse create(SkillDefinition definition) {
        checkDefinition(definition, null);
        String text = json.writeValueAsString(definition);
        UUID id = db.transactionally(() -> {
            UUID created = repository.createCustom(definition.id(), definition.slash(), text)
                    .orElseThrow(() -> new WorkServiceException(WorkErrorCode.SKILL_ID_TAKEN, "TAKEN"));
            audit.skill(AuditAction.TASK_SKILL_CUSTOM_SAVED, created, "CREATED");
            return created;
        });
        return get(id);
    }

    public CustomSkillResponse update(UUID id, SkillDefinition definition) {
        CustomRow current = row(id);
        if (!current.skillId().equals(definition.id())) {
            throw new WorkServiceException(WorkErrorCode.DEFINITION_INVALID, "ID_CHANGED",
                    List.of("id: a custom skill's id cannot change"));
        }
        checkDefinition(definition, id);
        String text = json.writeValueAsString(definition);
        try {
            db.transactionally(() -> {
                if (!repository.newVersion(id, definition.slash(), text)) {
                    throw new WorkServiceException(WorkErrorCode.CUSTOM_SKILL_STATE, "ARCHIVED");
                }
                audit.skill(AuditAction.TASK_SKILL_CUSTOM_SAVED, id, "VERSION");
                return null;
            });
        } catch (DuplicateKeyException e) {
            throw new WorkServiceException(WorkErrorCode.SKILL_ID_TAKEN, "SLASH_TAKEN");
        }
        return get(id);
    }

    public CustomSkillResponse approve(UUID id) {
        row(id);
        db.transactionally(() -> {
            if (!repository.approve(id)) throw new WorkServiceException(WorkErrorCode.CUSTOM_SKILL_STATE, "NOT_DRAFT");
            audit.skill(AuditAction.TASK_SKILL_CUSTOM_APPROVED, id, null);
            return null;
        });
        return get(id);
    }

    public CustomSkillResponse archive(UUID id) {
        row(id);
        db.transactionally(() -> {
            if (!repository.archive(id)) throw new WorkServiceException(WorkErrorCode.CUSTOM_SKILL_STATE, "ARCHIVED");
            audit.skill(AuditAction.TASK_SKILL_CUSTOM_ARCHIVED, id, null);
            return null;
        });
        return get(id);
    }

    public List<CustomSkillResponse> customSkills() {
        return repository.live().stream().map(this::response).toList();
    }

    public CustomSkillResponse get(UUID id) {
        return response(row(id));
    }

    private CustomRow row(UUID id) {
        return repository.find(id).orElseThrow(() -> new WorkServiceException(WorkErrorCode.CUSTOM_SKILL_NOT_FOUND,
                "NOT_FOUND"));
    }

    private void checkDefinition(SkillDefinition definition, UUID self) {
        List<String> errors = SkillRules.validate(definition, false);
        if (!errors.isEmpty()) throw new WorkServiceException(WorkErrorCode.DEFINITION_INVALID, "RULES", errors);
        if (builtIn.contains(definition.id()) || builtIn.slashTaken(definition.slash())) {
            throw new WorkServiceException(WorkErrorCode.SKILL_ID_TAKEN, "BUILT_IN");
        }
        boolean clash = repository.live().stream().filter(r -> !r.id().equals(self))
                .anyMatch(r -> r.skillId().equals(definition.id()) || r.slash().equals(definition.slash()));
        if (clash) throw new WorkServiceException(WorkErrorCode.SKILL_ID_TAKEN, "TAKEN");
    }

    private CustomSkillResponse response(CustomRow row) {
        return new CustomSkillResponse(row.id(), row.skillId(), CustomSkillState.valueOf(row.state()), row.version(),
                catalog.definition(row), row.createdAt(), row.updatedAt(), row.approvedAt());
    }
}
