package com.verso.document.api;

import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-process contract for knowledge derived from documents (ADR-0049, the qa module's verified answers): the chunks of
 * cited pages with their content hashes, and a retrieval check for the nightly golden-set evaluation. No model call in
 * any method. Only modules that list {@code document :: api} reach it; only the qa module calls it.
 */
public interface DocumentProvenance {

    /** At most this many pages (and documents) per call: the citations of one answer. */
    int MAX_PAGES = 50;

    /**
     * The chunks of those {@code pages} whose READY document the reader's principals reach (ADR-0028, in the query),
     * with text, names and hashes; ordered by document, page and position. A page the reader may not read and a page
     * that does not exist give the same empty result.
     *
     * @param reader the validated token's sub and groups (never taken from a request)
     */
    List<SourceChunk> sourceChunks(Reader reader, Collection<SourcePage> pages);

    /**
     * The same chunks without any reader filter, for the system's own staleness sweep: ids, units, collections and
     * hashes only (no text, no names), so nothing a caller could not read leaves the document module.
     */
    List<SourceChunk> sourceHashes(Collection<SourcePage> pages);

    /**
     * The nightly retrieval check (ADR-0049): the ids of the top {@code topK} chunks hybrid search returns for the
     * question, searched as the audience of {@code audienceOf} (the union of those documents' reader principals), with
     * the stored question vector instead of a new embedding. Ids only, no text. Empty when the documents are gone or
     * the vector was made by another embedding model (nothing would be comparable).
     *
     * @param audienceOf the cited documents (at most {@value #MAX_PAGES})
     * @param question   the question text, for the full-text half of the search; not logged
     */
    List<UUID> rankForAudience(Collection<UUID> audienceOf, String question, QuestionEmbedding embedding, int topK);
}
