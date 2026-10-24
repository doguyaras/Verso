package com.verso.privacy.api.dto;

/**
 * DELETE /v1/admin/subjects/{sub}: what the erasure removed and what it kept on purpose (ADR-0034). A repeat of the
 * request removes nothing more and reports zeros for the deleted parts.
 *
 * @param historyDeleted           conversations, turns, feedback and activity days removed
 * @param personalDocumentsDeleted personal documents removed with their file, pages, chunks, vectors and readers
 * @param answersRedacted          answers of other accounts that cited one of those documents, replaced by the notice
 * @param kept                     what stays: collection documents, grants and created collections (the admin
 *                                 decides), and the audit rows (retention, ADR-0026)
 */
public record SubjectDeletionResponse(SubjectHistory historyDeleted, long personalDocumentsDeleted, long answersRedacted,
                                      Kept kept) {

    /**
     * @param documents        the collection documents and collections that name the subject (personal ones: none left)
     * @param auditEventsAsActor audit rows of the subject as the actor, kept until their retention ends
     */
    public record Kept(SubjectDocuments documents, long auditEventsAsActor) {
    }

    /** Ids only in this response's lists; the record is still never logged as a whole. */
    @Override
    public String toString() {
        return "SubjectDeletionResponse[personalDocumentsDeleted=" + personalDocumentsDeleted + "]";
    }
}
