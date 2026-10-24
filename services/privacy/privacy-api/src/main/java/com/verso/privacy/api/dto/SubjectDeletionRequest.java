package com.verso.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * DELETE /v1/admin/subjects/{sub} body. {@code confirm} must repeat the sub of the path exactly: an erasure cannot be
 * fired by a stray request, a retried GET or a copy-paste of the wrong path.
 */
public record SubjectDeletionRequest(@NotBlank @Size(max = 255) String confirm) {

    @Override
    public String toString() {
        return "SubjectDeletionRequest[confirm=***]";
    }
}
