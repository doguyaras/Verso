package com.verso.document.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Create or rename a collection (ADR-0028, verso-admin only). The name is shown to every reader (picker, citation
 * badge), so control characters and bidirectional overrides are refused like a conversation title (ADR-0023).
 *
 * @param name        1-100 characters, trimmed by the server; unique ignoring case
 * @param description optional, at most 500 characters
 */
public record CollectionRequest(
        @NotBlank @Size(max = 100)
        @Pattern(regexp = "[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*",
                message = "must not contain control characters or bidirectional overrides")
        String name,
        @Size(max = 500)
        @Pattern(regexp = "[^\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]*",
                message = "must not contain control characters or bidirectional overrides")
        String description) {
}
