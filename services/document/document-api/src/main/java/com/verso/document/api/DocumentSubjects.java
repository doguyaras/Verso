package com.verso.document.api;

import com.verso.document.api.dto.SubjectDocuments;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * In-process contract for KVKK data-subject requests (ADR-0034): what the document module holds about one token
 * {@code sub}, and the erasure of the subject's personal documents. Collection documents the subject uploaded belong
 * to the organisation (ADR-0028): they are reported, never deleted here; verso-admin decides one by one through
 * {@code DELETE /v1/documents/{id}}. Every method is refused to a caller without {@code verso-admin}, whichever module
 * calls it (ADR-0019, defence in depth).
 */
public interface DocumentSubjects {

    /** At most this many documents (and collection ids) per list; the counts are exact. */
    int MAX_LISTED = 1_000;

    /** At most this many personal documents per erasure batch (one short transaction). */
    int MAX_DELETE_BATCH = 100;

    /**
     * The subject's documents (personal and uploaded into a collection) with names and states, never content, and the
     * collections that name the subject: granted to {@code user:<sub>} or created by the subject.
     */
    SubjectDocuments find(String sub);

    /** Ids of up to {@code limit} personal documents of {@code sub}, in id order; empty when none is left. */
    List<UUID> personalDocumentIds(String sub, int limit);

    /**
     * Deletes those of {@code documentIds} that are still personal documents of {@code sub}, in one transaction, each
     * with its original file, pages, chunks, embeddings, search vectors and reader rows (ON DELETE CASCADE), and returns
     * the ids it deleted. Idempotent: a repeat deletes nothing. Serialised with the subject's uploads and with a
     * concurrent erasure of the same subject.
     */
    List<UUID> deletePersonalDocuments(String sub, Collection<UUID> documentIds);
}
