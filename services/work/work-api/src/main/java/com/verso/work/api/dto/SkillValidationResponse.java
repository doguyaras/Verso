package com.verso.work.api.dto;

import java.util.List;

/**
 * The validator's verdict on a definition (ADR-0057), for the admin's JSON editor.
 *
 * @param errors one line per problem, {@code path: message}; empty when valid
 */
public record SkillValidationResponse(boolean valid, List<String> errors) {
}
