package com.verso.document.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One collection as the caller sees it (ADR-0028). Grants are not part of it: who may read is directory data and is
 * listed only to verso-admin ({@link CollectionGrantResponse}).
 *
 * @param documentCount documents in the collection, whatever their status
 * @param readable      the caller's principals match a grant: its documents are listed and searched for the caller.
 *                      Always true for a non-admin (other collections are not listed); an admin manages every
 *                      collection but reads only the granted ones (least privilege)
 * @param manageable    the caller is verso-admin
 */
public record CollectionResponse(UUID id, String name, String description, long documentCount, boolean readable,
                                 boolean manageable, Instant createdAt, Instant updatedAt) {
}
