package com.verso.privacy.api.dto;

/**
 * GET /v1/admin/subjects/{sub}: what Verso holds about one token subject (KVKK md. 11, ADR-0034).
 *
 * @param auditEventsAsActor audit rows in which the subject is the actor; kept until their retention (ADR-0026)
 */
public record SubjectSummaryResponse(SubjectHistory history, SubjectDocuments documents, long auditEventsAsActor) {
}
