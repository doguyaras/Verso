package com.verso.qa.controller;

import com.verso.platform.core.exception.ErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.AnswerStage;
import com.verso.qa.api.dto.StreamCitationsEvent;
import com.verso.qa.api.dto.StreamDeltaEvent;
import com.verso.qa.api.dto.StreamDoneEvent;
import com.verso.qa.api.dto.StreamErrorEvent;
import com.verso.qa.api.dto.StreamStageEvent;
import com.verso.qa.service.AnswerStream;
import com.verso.qa.service.QaAudit;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The server-sent events of one streamed answer (ADR-0035): {@code stage}*, {@code delta}*, then {@code citations} and
 * {@code done}, or {@code error}; keep-alive comments in between. Written by the one thread that answers the question;
 * the container's callbacks only mark the stream closed. Payloads are the qa-api records; nothing of them is logged.
 */
final class QuestionEventStream implements AnswerStream {

    static final String STAGE = "stage";
    static final String DELTA = "delta";
    static final String CITATIONS = "citations";
    static final String DONE = "done";
    static final String ERROR = "error";
    /**
     * A comment after this much silence: well below every read timeout on the way (the edge proxy's 150 s) and often
     * enough to notice a client that left while the model is still silent (a write to a closed socket fails).
     */
    static final long KEEP_ALIVE_NANOS = TimeUnit.SECONDS.toNanos(10);
    /** The JSON path's Retry-After for a 503 (GlobalServiceExceptionHandler), as the error event's retryAfter. */
    static final int RETRY_AFTER_SECONDS = 5;

    private static final Logger log = LoggerFactory.getLogger(QuestionEventStream.class);

    private final SseEmitter emitter;
    private final QaAudit audit;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private volatile long lastWrite = System.nanoTime();

    /** @param timeout the backstop of the whole stream; the answering thread's own bounds end it earlier */
    QuestionEventStream(Duration timeout, QaAudit audit) {
        this.emitter = new SseEmitter(timeout.toMillis());
        this.audit = audit;
        // The client left (the container saw it), the stream ended, or the backstop fired: the next write gives up.
        emitter.onCompletion(() -> open.set(false));
        emitter.onError(e -> open.set(false));
        emitter.onTimeout(() -> {
            open.set(false);
            emitter.complete();
        });
    }

    SseEmitter emitter() {
        return emitter;
    }

    @Override
    public boolean stage(AnswerStage stage, int retrieved, int relevant) {
        boolean counts = stage == AnswerStage.PASSAGES_FOUND;
        return send(STAGE, new StreamStageEvent(stage, counts ? retrieved : null, counts ? relevant : null));
    }

    @Override
    public boolean delta(String text) {
        return send(DELTA, new StreamDeltaEvent(text));
    }

    @Override
    public boolean keepAlive() {
        if (!open.get()) return false;
        if (System.nanoTime() - lastWrite < KEEP_ALIVE_NANOS) return true;
        try {
            emitter.send(SseEmitter.event().comment("keep-alive"));
            lastWrite = System.nanoTime();
            return true;
        } catch (IOException | IllegalStateException e) {
            open.set(false);
            return false;
        }
    }

    @Override
    public void completed(AnswerResponse answer) {
        // After the turn is stored, like the JSON path: ids and the outcome type only (ADR-0026).
        audit.questionAsked(answer);
        if (send(CITATIONS, new StreamCitationsEvent(answer.answer(), answer.citations()))) {
            send(DONE, StreamDoneEvent.of(answer));
        }
        end();
    }

    @Override
    public void failed(ServiceException failure) {
        ErrorCode code = failure.getErrorCode();
        HttpStatus status = code.getHttpStatus();
        // The structured line the global handler writes for the JSON path; the reason is a fixed code (reference 7.4).
        if (status.is5xxServerError()) {
            log.error("Question stream failed: code={} status={} reason={} exceptionType={}", name(code), status.value(),
                    failure.getSafeLogReason(), failure.getClass().getSimpleName());
        } else {
            log.warn("Question stream rejected: code={} status={} reason={}", name(code), status.value(),
                    failure.getSafeLogReason());
        }
        Integer retryAfter = status == HttpStatus.SERVICE_UNAVAILABLE ? RETRY_AFTER_SECONDS : null;
        send(ERROR, new StreamErrorEvent(code.getCode(), code.getMessage(), retryAfter));
        end();
    }

    @Override
    public void cancelled() {
        end();
    }

    private boolean send(String name, Object data) {
        if (!open.get()) return false;
        try {
            emitter.send(SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON));
            lastWrite = System.nanoTime();
            return true;
        } catch (IOException | IllegalStateException e) {
            // The client is gone or the stream already ended; the container reports it, nothing to log here.
            open.set(false);
            return false;
        }
    }

    private void end() {
        open.set(false);
        try {
            emitter.complete();
        } catch (IllegalStateException e) {
            // Already completed by the container (client gone, backstop): nothing left to end.
        }
    }

    private static String name(ErrorCode code) {
        return code instanceof Enum<?> e ? e.name() : String.valueOf(code.getCode());
    }
}
