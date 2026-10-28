package com.verso.qa.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A model provider connection as the panel shows it (ADR-0056). Never the key: only whether one is stored and its last
 * four characters ({@code keyHint}, e.g. {@code …a1b2}).
 *
 * @param locality         LOCAL (the installation's internal network) or EXTERNAL (data leaves the installation)
 * @param egressHost       the host the connection reaches (the egress allowlist entry it adds in cloud mode)
 * @param validationStatus OK, AUTH_FAILED, UNREACHABLE, MODEL_NOT_FOUND, RATE_LIMITED or FAILED; null: never tested
 */
public record ModelConnectionResponse(UUID id, String name, ModelProviderType type, String baseUrl, String region,
                                      String deployment, String project, List<String> models, boolean enabled,
                                      String locality, String egressHost, boolean keyStored, String keyHint,
                                      int keyVersion, Instant keyRotatedAt, Instant validatedAt,
                                      String validationStatus, Instant createdAt, Instant updatedAt) {
    public ModelConnectionResponse {
        models = List.copyOf(models);
    }
}
