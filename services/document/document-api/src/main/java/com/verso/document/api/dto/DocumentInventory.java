package com.verso.document.api.dto;

import java.util.List;

/** A bounded list of documents and how many match in total (ADR-0023: documents never cited). */
public record DocumentInventory(long total, List<DocumentSummary> documents) {

    public DocumentInventory {
        documents = List.copyOf(documents);
    }
}
