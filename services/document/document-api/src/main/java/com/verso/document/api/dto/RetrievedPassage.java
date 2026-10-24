package com.verso.document.api.dto;

import com.verso.document.api.enums.SourceUnit;
import java.util.UUID;

/**
 * One retrieved chunk: where it comes from (document, file name, page or section number and which of the two, ADR-0016;
 * the collection holding the document, ADR-0028) for the citation, its text for the prompt, and two signals for the
 * answerability decision (ADR-0022):
 *
 * <ul>
 *   <li>{@code similarity}: cosine similarity of the chunk to the question (1 = identical direction). It is computed for
 *       every passage, also one that only the full-text search found; the fused rank never replaces it.</li>
 *   <li>{@code specificTermShare}: the share of the reader's searchable chunks that contain the rarest question term
 *       this chunk contains. A small share means the chunk names something specific from the question (a code, an
 *       abbreviation); 1 when it shares no term with the question or the term is common (at least
 *       {@link com.verso.document.api.DocumentRetrieval#TERM_SHARE_LIMIT}).</li>
 * </ul>
 *
 * {@code collectionId} and {@code collectionName} are null for a personal document.
 */
public record RetrievedPassage(UUID documentId, String fileName, int pageNumber, SourceUnit unit, String content,
                               double similarity, double specificTermShare, UUID collectionId, String collectionName) {

    /** A passage of a personal document. */
    public RetrievedPassage(UUID documentId, String fileName, int pageNumber, SourceUnit unit, String content,
                            double similarity, double specificTermShare) {
        this(documentId, fileName, pageNumber, unit, content, similarity, specificTermShare, null, null);
    }

    /** A passage without lexical evidence (specificTermShare 1). */
    public RetrievedPassage(UUID documentId, String fileName, int pageNumber, SourceUnit unit, String content,
                            double similarity) {
        this(documentId, fileName, pageNumber, unit, content, similarity, 1.0);
    }
}
