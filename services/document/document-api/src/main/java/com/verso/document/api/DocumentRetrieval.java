package com.verso.document.api;

import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import java.util.List;

/**
 * In-process contract for other modules (ADR-0003, ADR-0007 #24): the passages of the READY documents a reader may read
 * that are closest to a question. Access is part of every query itself, never a filter afterwards (llm-rules 4.1,
 * ADR-0028): a passage of a document outside the reader's principals can never be returned, ranked or counted.
 */
public interface DocumentRetrieval {

    /**
     * Term shares at or above this are reported as 1 ("common"): only rare terms are counted exactly, which bounds
     * the counting work of a question on a large account (ADR-0022). An answerability rule that reads
     * {@link RetrievedPassage#specificTermShare()} must use a limit below it.
     */
    double TERM_SHARE_LIMIT = 0.10;

    /**
     * Hybrid search (ADR-0022): the nearest chunks by embedding and the best chunks by full-text rank, fused by
     * Reciprocal Rank Fusion; best fused rank first. Both candidate lists only hold chunks of documents whose principals
     * meet the reader's (ADR-0028). Embeds the question with the same local model as the documents (llm-rules 1.2, 6.1);
     * fails with a document error when a readable document was indexed with another model or the model is unreachable.
     *
     * @param reader   the validated token's sub and groups (never taken from a request)
     * @param question the question text; not logged
     * @param topK     maximum number of passages
     */
    List<RetrievedPassage> search(Reader reader, String question, int topK);

    /** {@link #search(Reader, String, int)} for a reader without groups: personal documents and direct user grants. */
    default List<RetrievedPassage> search(String accountId, String question, int topK) {
        return search(Reader.personal(accountId), question, topK);
    }
}
