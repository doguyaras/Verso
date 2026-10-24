package com.verso.document.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * What the document module holds about one data subject (ADR-0034). Lists are capped (DocumentSubjects.MAX_LISTED),
 * counts are exact.
 *
 * @param personalCount        personal documents of the subject
 * @param personal             the first of them, oldest first
 * @param collectionCount      documents the subject uploaded into a collection (the organisation's; reported only)
 * @param collection           the first of them, oldest first
 * @param grantedCollections   collections granted to {@code user:<sub>} (access rights, not content)
 * @param createdCollections   collections whose {@code created_by} is the subject
 */
public record SubjectDocuments(long personalCount, List<SubjectDocument> personal, long collectionCount,
                               List<SubjectDocument> collection, List<UUID> grantedCollections,
                               List<UUID> createdCollections) {

    public SubjectDocuments {
        personal = List.copyOf(personal);
        collection = List.copyOf(collection);
        grantedCollections = List.copyOf(grantedCollections);
        createdCollections = List.copyOf(createdCollections);
    }
}
