package com.verso.document.api;

import com.verso.document.api.dto.Passage;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.UUID;

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

    /**
     * Embeds the question with the documents' own local model (llm-rules 1.2, 6.1), under the same bulkhead and time
     * bound as {@link #search(Reader, String, int)} (ADR-0008). ADR-0049: the qa module embeds once, looks up its
     * verified answers with the vector, and only then searches with the same vector, so the hot path keeps one embedding
     * call. Fails with a document error when the model is unreachable.
     *
     * @param question the question text; not logged
     */
    QuestionEmbedding embed(String question);

    /**
     * {@link #search(Reader, String, int)} with a vector from {@link #embed(String)}: no embedding call. A vector of
     * another model or dimension fails like an unreachable model (it cannot be compared with the chunks).
     */
    List<RetrievedPassage> search(Reader reader, String question, QuestionEmbedding embedding, int topK);

    /** {@link #search(Reader, String, int)} for a reader without groups: personal documents and direct user grants. */
    default List<RetrievedPassage> search(String accountId, String question, int topK) {
        return search(Reader.personal(accountId), question, topK);
    }

    /**
     * One passage by its id (ADR-0031), when its document is READY and the reader's principals reach it, checked in
     * the query (ADR-0028). An id that does not exist and one the reader may not read are the same empty result: the
     * caller cannot learn that a passage exists. No model call.
     *
     * @param reader    the validated token's sub and groups (never taken from a request)
     * @param passageId a {@link RetrievedPassage#passageId()}
     */
    Optional<Passage> passage(Reader reader, UUID passageId);

    /** ADR-0060: whether {@link #relevance(String, String)} can score at all (the reranker of ADR-0043 is on). */
    default boolean rerankerEnabled() {
        return false;
    }

    /**
     * ADR-0060: the reranker's relevance of {@code text} to {@code question}, a sigmoid score in 0..1, under the
     * reranker's own deadline, bulkhead and circuit breaker (ADR-0043). Empty when the reranker is off, skipped or
     * failed: a caller that uses it as a check must treat empty as "not confirmed". A remote call to the internal
     * sidecar; neither text is logged.
     */
    default OptionalDouble relevance(String question, String text) {
        return OptionalDouble.empty();
    }
}
