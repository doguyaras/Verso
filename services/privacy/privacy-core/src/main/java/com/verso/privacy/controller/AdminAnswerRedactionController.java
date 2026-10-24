package com.verso.privacy.controller;

import com.verso.platform.security.authority.VersoRoles;
import com.verso.privacy.api.dto.AnswerRedactionRequest;
import com.verso.privacy.api.dto.AnswerRedactionResponse;
import com.verso.privacy.service.SubjectRequestService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * POST /v1/admin/documents/{documentId}/answer-redaction (ADR-0034): after a document is deleted, verso-admin may
 * replace the stored answers that cited it, in every account's history, with a fixed notice (the answer may quote
 * personal data from the document). Questions, citation references (shown as "deleted"), ratings and counters stay.
 */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/admin/documents")
public class AdminAnswerRedactionController {

    private final SubjectRequestService subjects;

    public AdminAnswerRedactionController(SubjectRequestService subjects) {
        this.subjects = subjects;
    }

    /** The body must repeat the document id ({@code {"confirm": "<documentId>"}}); 409 while the document exists. */
    @PostMapping("/{documentId}/answer-redaction")
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ResponseEntity<AnswerRedactionResponse> redact(@PathVariable("documentId") UUID documentId,
                                                          @Valid @RequestBody AnswerRedactionRequest request) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .body(subjects.redactAnswers(documentId, request));
    }
}
