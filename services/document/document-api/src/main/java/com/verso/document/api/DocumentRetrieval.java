package com.verso.document.api;

import com.verso.document.api.dto.RetrievedPassage;
import java.util.List;

/**
 * In-process contract for other modules (ADR-0003, ADR-0007 #24): the passages of an account's READY documents
 * closest to a question. Ownership is part of the vector query itself, never a filter afterwards (llm-rules 4.1):
 * another account's passage can never be returned.
 */
public interface DocumentRetrieval {

    /**
     * Best passages first. Embeds the question with the same local model as the documents (llm-rules 1.2, 6.1); fails
     * with a document error when the account's documents were indexed with another model or the model is unreachable.
     *
     * @param accountId the validated token's sub (never taken from a request)
     * @param question  the question text; not logged
     * @param topK      maximum number of passages
     */
    List<RetrievedPassage> search(String accountId, String question, int topK);
}
