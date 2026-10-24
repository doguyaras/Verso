package com.verso.qa.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * A new title for one of the caller's conversations (ADR-0023): 1-120 characters, trimmed by the server. Control
 * characters and bidirectional overrides are refused: a title is shown in lists, where an override could disguise it.
 */
public record RenameConversationRequest(
        @NotBlank @Size(max = 120)
        @Pattern(regexp = "[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*",
                message = "must not contain control characters or bidirectional overrides")
        String title) {
}
