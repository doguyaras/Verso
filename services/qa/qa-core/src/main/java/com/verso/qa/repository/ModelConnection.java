package com.verso.qa.repository;

import com.verso.qa.api.dto.ModelConnectionResponse;
import com.verso.qa.api.dto.ModelProviderType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A row of {@code qa.model_connection} (V8, ADR-0056). The sealed key travels with the row but is opened only by the
 * client factory; {@link #toString()} names neither the key nor its ciphertext.
 *
 * @param revision bumped by every change (settings, key, enabled): the client cache is keyed by it
 */
public record ModelConnection(UUID id, String name, ModelProviderType type, String baseUrl, String region,
                              String deployment, String project, List<String> models, boolean enabled,
                              Locality locality, String egressHost, SealedKey key, String keyHint,
                              int keyVersion, Instant keyRotatedAt, Instant validatedAt, String validationStatus,
                              Instant createdAt, Instant updatedAt, long revision) {

    public enum Locality { LOCAL, EXTERNAL }

    public ModelConnection {
        models = List.copyOf(models);
    }

    public boolean external() {
        return locality == Locality.EXTERNAL;
    }

    public ModelConnectionResponse toResponse() {
        return new ModelConnectionResponse(id, name, type, baseUrl, region, deployment, project, models, enabled,
                locality.name(), egressHost, key != null, keyHint, keyVersion, keyRotatedAt, validatedAt,
                validationStatus, createdAt, updatedAt);
    }

    @Override
    public String toString() {
        return "ModelConnection[id=" + id + ", type=" + type + ", locality=" + locality + ", revision=" + revision + "]";
    }
}
