package com.verso.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/admin/subjects/summary and /export body: the token {@code sub} of the data subject. In the body, never in
 * the URL: a path is echoed in error bodies and kept by proxies and traces, a body is not (ADR-0034).
 */
public record SubjectRequest(@NotBlank @Size(max = 255) String sub) {

    @Override
    public String toString() {
        return "SubjectRequest[sub=***]";
    }
}
