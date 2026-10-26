package com.verso.qa.api.dto;

import java.time.Instant;

/**
 * A hook rule (ADR-0052): a trigger, one allowed action and whether it is on. The set of rules is fixed; admins only
 * switch them on and off.
 *
 * @param key            stable key (CARD_STALE_NOTIFY, ...)
 * @param description    what the rule does, in Turkish
 * @param defaultEnabled the documented default of the rule
 */
public record HookRuleResponse(String key, HookTrigger trigger, HookAction action, boolean enabled,
                               boolean defaultEnabled, String description, Instant updatedAt) {
}
