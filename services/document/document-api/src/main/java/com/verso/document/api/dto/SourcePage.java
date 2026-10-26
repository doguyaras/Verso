package com.verso.document.api.dto;

import java.util.Objects;
import java.util.UUID;

/**
 * A cited page or section of a document (ADR-0049): what a verified answer is bound to.
 *
 * @param pageNumber a page or section number, as the document's unit says (ADR-0016)
 */
public record SourcePage(UUID documentId, int pageNumber) {

    public SourcePage {
        Objects.requireNonNull(documentId, "documentId");
        if (pageNumber < 1) throw new IllegalArgumentException("page number must be positive");
    }
}
