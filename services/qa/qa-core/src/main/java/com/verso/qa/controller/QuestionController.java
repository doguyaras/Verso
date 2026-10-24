package com.verso.qa.controller;

import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.QuestionRequest;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.QuestionService;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /v1/questions (ADR-0004, ADR-0008). Answers quote the caller's documents: {@code Cache-Control: private, no-store}. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/questions")
public class QuestionController {

    private final QuestionService service;
    private final QaAudit audit;

    public QuestionController(QuestionService service, QaAudit audit) {
        this.service = service;
        this.audit = audit;
    }

    @PostMapping
    public ResponseEntity<AnswerResponse> ask(@CurrentAccount AccountId account, @Valid @RequestBody QuestionRequest request) {
        AnswerResponse answer = service.ask(account, request.question(), request.conversationId());
        // After the answer exists (and its turn is stored); ids and the outcome type only (ADR-0026).
        audit.questionAsked(answer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(answer);
    }
}
