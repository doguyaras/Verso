package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * One passage (chunk) read by its id (ADR-0031): where it comes from and its text. Returned only when the reader's
 * principals reach its READY document (ADR-0028); the text is document content and is never logged (llm-rules 2.1).
 *
 * @param passageId      the chunk's id, as {@link RetrievedPassage#passageId()} reported it
 * @param fileName       the document's name; never logged
 * @param pageNumber     a page or section number, as {@code unit} says (ADR-0016)
 * @param collectionId   the collection holding the document, null for a personal document
 * @param collectionName that collection's name, null for a personal document
 */
public record Passage(UUID passageId, UUID documentId, String fileName, int pageNumber, SourceUnit unit,
                      String content, UUID collectionId, String collectionName) {
}
