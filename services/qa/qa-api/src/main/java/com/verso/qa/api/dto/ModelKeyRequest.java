package com.verso.qa.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** A new API key for a connection (rotation, ADR-0056). Write-only like {@link ModelConnectionRequest#apiKey()}. */
public record ModelKeyRequest(
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @NotBlank @Size(max = 8192) String apiKey) {

    @Override
    public String toString() {
        return "ModelKeyRequest[apiKey=***]";
    }
}
