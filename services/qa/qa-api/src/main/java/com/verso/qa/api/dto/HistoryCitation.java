package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * A citation of a stored answer (ADR-0023): the same number, document and page as {@link Citation}, without the
 * excerpt (passage text is not kept in the history). {@code fileName} comes from the document when the history is
 * read, with the reader's access at that moment (ADR-0028):
 *
 * <ul>
 *   <li>readable: {@code fileName} set, {@code documentAccessible} true, {@code documentDeleted} false;</li>
 *   <li>the reader lost access (grant revoked, group left, document moved): {@code fileName} null,
 *       {@code documentAccessible} false, {@code documentDeleted} false;</li>
 *   <li>deleted: {@code fileName} null, {@code documentAccessible} false, {@code documentDeleted} true.</li>
 * </ul>
 *
 * {@code documentAccessible}, {@code collectionId} and {@code collectionName} were added last, so the change is
 * additive; the collection fields are null for a personal or inaccessible document.
 */
public record HistoryCitation(int number, UUID documentId, String fileName, int page, CitationUnit unit,
                              boolean documentDeleted, boolean documentAccessible, UUID collectionId,
                              String collectionName) {

    /** A citation of a personal document: accessible unless deleted. */
    public HistoryCitation(int number, UUID documentId, String fileName, int page, CitationUnit unit,
                           boolean documentDeleted) {
        this(number, documentId, fileName, page, unit, documentDeleted, !documentDeleted, null, null);
    }
}
