package com.verso.work.service;

import com.verso.work.api.dto.AdminSkillResponse;
import com.verso.work.api.dto.CustomSkillResponse;
import com.verso.work.api.dto.SkillDefinition;
import com.verso.work.api.dto.SkillValidationResponse;
import java.util.List;
import java.util.UUID;

/**
 * The verso-admin side of the skill library (ADR-0057): the installation's switch per skill and the customer-authored
 * skills (DRAFT, new version = DRAFT again, approve, archive).
 */
public interface SkillAdminService {

    List<AdminSkillResponse> list();

    AdminSkillResponse setEnabled(String skillId, boolean enabled);

    /** The same rules as a save; nothing stored. */
    SkillValidationResponse validate(SkillDefinition definition);

    CustomSkillResponse create(SkillDefinition definition);

    CustomSkillResponse update(UUID id, SkillDefinition definition);

    CustomSkillResponse approve(UUID id);

    CustomSkillResponse archive(UUID id);

    List<CustomSkillResponse> customSkills();

    CustomSkillResponse get(UUID id);
}
