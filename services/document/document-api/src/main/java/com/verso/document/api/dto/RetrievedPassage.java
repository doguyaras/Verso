package com.verso.document.api.dto;

import java.util.UUID;

/**
 * One retrieved chunk: where it comes from (document, file name, page) for the citation, its text for the prompt, and
 * its cosine similarity to the question (1 = identical direction).
 */
public record RetrievedPassage(UUID documentId, String fileName, int pageNumber, String content, double similarity) {
}
