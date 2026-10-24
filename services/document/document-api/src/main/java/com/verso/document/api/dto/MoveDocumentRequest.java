package com.verso.document.api.dto;

import java.util.UUID;

/**
 * Where a document goes (ADR-0028, verso-admin only): a collection, or {@code null} for its uploader's personal space.
 * Only the admin's own personal documents and collection documents can be moved; another user's personal document
 * does not exist for the admin (ADR-0019).
 */
public record MoveDocumentRequest(UUID collectionId) {
}
