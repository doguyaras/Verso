package com.verso.qa.service;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;

/** POST /v1/questions: an answer from the caller's own documents, with server-side citations (ADR-0008). */
public interface QuestionService {

    AnswerResponse ask(AccountId account, String question);
}
