package com.verso.document.api.dto;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A READY document of a shared collection, as the diagnostics see it (ADR-0052): ids and its creation time only.
 *
 * @param createdAt when this document (this version of the file) was ingested
 */
public record SharedDocument(UUID id, UUID collectionId, Instant createdAt) {

    public SharedDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(collectionId, "collectionId");
        Objects.requireNonNull(createdAt, "createdAt");
    }
}
