package com.verso.qa.api.dto;

import java.util.UUID;

/**
 * A citation of a stored answer (ADR-0023): the same number, document and page as {@link Citation}, without the
 * excerpt (passage text is not kept in the history). {@code fileName} comes from the document when the history is
 * read; it is null and {@code documentDeleted} is true once the document has been deleted.
 */
public record HistoryCitation(int number, UUID documentId, String fileName, int page, CitationUnit unit,
                              boolean documentDeleted) {
}
