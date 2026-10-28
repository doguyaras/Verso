package com.verso.work.api.dto;

import com.verso.work.api.enums.CustomSkillState;
import java.util.UUID;

/**
 * One skill on the admin screen (ADR-0057): every built-in one and every customer-authored one, with whether users
 * see it.
 *
 * @param enabled       users see and run it (built-in: the installation switch; custom: approved and switched on)
 * @param customSkillId customer-authored skills only
 * @param state         customer-authored skills only
 * @param customVersion customer-authored skills only: the current version number
 */
public record AdminSkillResponse(SkillSummary skill, boolean enabled, UUID customSkillId, CustomSkillState state,
                                 Integer customVersion) {
}
