package com.verso.qa.controller;

import com.verso.platform.security.web.AccountId;
import com.verso.platform.security.web.CurrentAccount;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.QuestionRequest;
import com.verso.qa.config.AiMode;
import com.verso.qa.config.QaProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.service.QaAudit;
import com.verso.qa.service.QuestionService;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** /v1/questions (ADR-0004, ADR-0008). Answers quote the caller's documents: {@code Cache-Control: private, no-store}. */
@RestController
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@RequestMapping("/v1/questions")
public class QuestionController {

    /** Above retrieval's own bound (the embedding call's 10 s) and the history write, below nothing on the way. */
    static final Duration STREAM_MARGIN = Duration.ofSeconds(30);

    private final QuestionService service;
    private final QaAudit audit;
    private final QaProperties properties;
    private final VersoAiProperties ai;

    public QuestionController(QuestionService service, QaAudit audit, QaProperties properties, VersoAiProperties ai) {
        this.service = service;
        this.audit = audit;
        this.properties = properties;
        this.ai = ai;
    }

    @PostMapping
    public ResponseEntity<AnswerResponse> ask(@CurrentAccount AccountId account, @Valid @RequestBody QuestionRequest request) {
        AnswerResponse answer = service.ask(account, request.question(), request.conversationId(), request.model());
        // After the answer exists (and its turn is stored); ids and the outcome type only (ADR-0026).
        audit.questionAsked(answer);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(answer);
    }

    /**
     * The same answer as server-sent events (ADR-0035). A question refused before any model call (open circuit,
     * foreign or full conversation, invalid body) is a plain error envelope with its HTTP status, as on the JSON
     * endpoint; once the stream is open, a failure is its last {@code error} event. {@code X-Accel-Buffering: no}
     * keeps a proxy that honours it from holding the events back (the edge proxy also turns buffering off).
     */
    @PostMapping("/stream")
    public ResponseEntity<SseEmitter> stream(@CurrentAccount AccountId account,
                                             @Valid @RequestBody QuestionRequest request) {
        QuestionEventStream events = new QuestionEventStream(streamTimeout(), audit);
        service.stream(account, request.question(), request.conversationId(), request.model(), events);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate())
                .header("X-Accel-Buffering", "no").contentType(MediaType.TEXT_EVENT_STREAM).body(events.emitter());
    }

    /** The backstop of a stream: the wait for a chat slot, the chat bound of the mode (ADR-0008) and the margin. */
    private Duration streamTimeout() {
        Duration chat = ai.mode() == AiMode.CLOUD ? properties.cloudChatTimeout() : properties.localChatTimeout();
        return properties.chatWait().plus(chat).plus(STREAM_MARGIN);
    }
}
