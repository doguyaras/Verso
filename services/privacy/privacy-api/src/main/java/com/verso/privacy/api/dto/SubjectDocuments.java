package com.verso.privacy.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * The subject's documents and the collections that name the subject. Each list holds at most 1 000 entries; the counts
 * are exact and {@code listsTruncated} says whether a list was cut.
 *
 * @param collection         documents the subject uploaded into a shared collection: the organisation's, never
 *                           deleted by a subject request; verso-admin decides one by one
 * @param grantedCollections collections granted to the subject by name ({@code user:<sub>})
 * @param createdCollections collections the subject created (as verso-admin)
 */
public record SubjectDocuments(long personalCount, List<SubjectDocumentItem> personal, long collectionCount,
                               List<SubjectDocumentItem> collection, List<UUID> grantedCollections,
                               List<UUID> createdCollections, boolean listsTruncated) {

    public SubjectDocuments {
        personal = List.copyOf(personal);
        collection = List.copyOf(collection);
        grantedCollections = List.copyOf(grantedCollections);
        createdCollections = List.copyOf(createdCollections);
    }
}
