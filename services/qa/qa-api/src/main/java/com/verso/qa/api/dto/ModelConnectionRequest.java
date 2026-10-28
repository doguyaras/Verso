package com.verso.qa.api.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * A model provider connection as a verso-admin adds or changes it (ADR-0056). The type decides which of the optional
 * fields matter: {@code baseUrl} (OpenAI-compatible, Ollama, Azure, a proxy for the others), {@code region} (Bedrock,
 * Google Vertex location), {@code deployment} (the Azure deployment) and {@code project} (Google Vertex).
 *
 * <p>{@code apiKey} is <b>write-only</b>: it is read from a request and never written to JSON, a log or a string
 * ({@link #toString()} hides it). On a change, a missing key keeps the stored one; rotation has its own endpoint. For
 * Bedrock it is {@code <access key id>:<secret access key>}; for Google Vertex the service account JSON.
 *
 * @param enabled boxed: absent means "on" for a new connection and "unchanged" for a change
 */
public record ModelConnectionRequest(
        @NotBlank @Size(max = 80) @Pattern(regexp = "[\\p{L}\\p{N} ._()-]+") String name,
        @NotNull ModelProviderType type,
        @Size(max = 500) String baseUrl,
        @Size(max = 64) @Pattern(regexp = "[a-z0-9-]*") String region,
        @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9._-]*") String deployment,
        @Size(max = 200) @Pattern(regexp = "[a-z0-9-]*") String project,
        @NotNull @Size(min = 1, max = 20)
        List<@NotBlank @Size(max = 200) @Pattern(regexp = "[A-Za-z0-9._:/@-]+") String> models,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) @Size(max = 8192) String apiKey,
        Boolean enabled) {

    @Override
    public String toString() {
        return "ModelConnectionRequest[name=" + name + ", type=" + type + ", apiKey="
                + (apiKey == null ? "absent" : "***") + "]";
    }
}
