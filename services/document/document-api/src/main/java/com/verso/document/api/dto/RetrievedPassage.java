package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * One retrieved chunk: where it comes from (document, file name, page or section number and which of the two, ADR-0016)
 * for the citation, its text for the prompt, and its cosine similarity to the question (1 = identical direction).
 */
public record RetrievedPassage(UUID documentId, String fileName, int pageNumber, SourceUnit unit, String content,
                               double similarity) {
}
