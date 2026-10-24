package com.verso.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * POST /v1/admin/subjects/delete body. The subject travels in the body, never in the URL (paths reach error bodies,
 * proxies and traces). {@code confirm} must repeat {@code sub} exactly: an erasure cannot be fired by a stray request,
 * a retried read or a copy-paste of the wrong subject.
 */
public record SubjectDeletionRequest(@NotBlank @Size(max = 255) String sub, @NotBlank @Size(max = 255) String confirm) {

    @Override
    public String toString() {
        return "SubjectDeletionRequest[sub=***, confirm=***]";
    }
}
