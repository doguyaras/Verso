package com.verso.qa.service;

import com.verso.platform.security.web.AccountId;
import com.verso.qa.api.dto.AnswerResponse;
import java.util.UUID;

/** POST /v1/questions: an answer from the caller's own documents, with server-side citations (ADR-0008). */
public interface QuestionService {

    /**
     * @param conversationId one of the caller's conversations, or null for a new one (ADR-0023); the answer is stored
     *                       there, the history is not part of the prompt
     */
    AnswerResponse ask(AccountId account, String question, UUID conversationId);

    /**
     * POST /v1/questions/stream (ADR-0035): the same answer, written to {@code stream} as it is produced. The checks
     * that refuse a question before any model call (open circuit, foreign or full conversation) run on the caller's
     * thread and throw, so they stay plain HTTP errors; everything after runs on a background thread that reports to
     * {@code stream} and never throws.
     */
    void stream(AccountId account, String question, UUID conversationId, AnswerStream stream);
}
