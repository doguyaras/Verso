package com.verso.qa.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the qa block, 11000-11999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum QaErrorCode implements ErrorCode {

    // --- model (11000-11009); fail closed: never an empty or made-up answer (ADR-0008, llm-rules 2.3)
    MODEL_UNAVAILABLE(11001, "The language model is unavailable, try again later.", HttpStatus.SERVICE_UNAVAILABLE),
    MODEL_BUSY(11002, "The language model is busy, try again shortly.", HttpStatus.SERVICE_UNAVAILABLE),

    // --- request (11010-11019); the body is bounded before it is read (phase 5 review S2)
    QUESTION_BODY_TOO_LARGE(11010, "The request body is too large.", HttpStatus.CONTENT_TOO_LARGE),
    QUESTION_LENGTH_REQUIRED(11011, "The request must declare its Content-Length.", HttpStatus.LENGTH_REQUIRED),

    // --- history and feedback (11020-11029, ADR-0023); another account's id is "not found" (IDOR, reference 6.5)
    CONVERSATION_NOT_FOUND(11020, "Conversation not found.", HttpStatus.NOT_FOUND),
    TURN_NOT_FOUND(11021, "Answer not found.", HttpStatus.NOT_FOUND),
    /** verso.qa.history.max-turns-per-conversation reached: the client starts a new conversation. */
    CONVERSATION_FULL(11022, "The conversation is full, start a new one.", HttpStatus.CONFLICT),
    /** A WRONG_SOURCE rating names a citation number the answer does not have. */
    FEEDBACK_CITATION_UNKNOWN(11023, "The answer has no source with this number.", HttpStatus.BAD_REQUEST),

    // --- admin metrics (11030-11039, ADR-0023)
    METRICS_RANGE_INVALID(11030, "The date range is invalid or too long.", HttpStatus.BAD_REQUEST),

    // --- answer memory and golden set (11040-11059, ADR-0049); an unknown or foreign id is "not found"
    KNOWLEDGE_CARD_NOT_FOUND(11040, "Verified answer not found.", HttpStatus.NOT_FOUND),
    /** The answer has no sources, or a cited document is gone or no longer readable by the admin. */
    KNOWLEDGE_CARD_SOURCE_UNAVAILABLE(11041, "The answer's sources are not available.", HttpStatus.CONFLICT),
    /** Only a sourced answer (ANSWERED, with citations, not redacted) can be verified. */
    KNOWLEDGE_CARD_NOT_ANSWERED(11042, "Only an answer with sources can be verified.", HttpStatus.CONFLICT),
    /** The transition is not allowed in the card's state (or another admin changed it first). */
    KNOWLEDGE_CARD_STATE_CONFLICT(11043, "The verified answer's state does not allow this.", HttpStatus.CONFLICT),
    /** The text of a cited page changed since the card was made: a new card from a new answer is needed. */
    KNOWLEDGE_CARD_SOURCE_CHANGED(11044, "A source of this answer has changed.", HttpStatus.CONFLICT),
    /** The two-person rule: the second verification must come from another admin. */
    KNOWLEDGE_CARD_ALREADY_VERIFIED_BY_YOU(11045, "You have already verified this answer.", HttpStatus.CONFLICT),
    /** Verified knowledge is shared knowledge: a personal document cannot back a card. */
    KNOWLEDGE_CARD_PERSONAL_SOURCE(11046, "An answer citing a personal document cannot be verified.", HttpStatus.CONFLICT),
    /** A card for the same (normalised) question exists and is not archived. */
    KNOWLEDGE_CARD_DUPLICATE(11047, "A verified answer for this question already exists.", HttpStatus.CONFLICT),
    /** A golden-set check is already running (or today's scheduled run exists). */
    KNOWLEDGE_EVAL_RUNNING(11048, "A golden-set check is already running.", HttpStatus.CONFLICT),

    // --- knowledge health and hooks (11060-11069, ADR-0052)
    /** from..to is reversed or longer than verso.qa.diagnostics.max-range-days. */
    KNOWLEDGE_HEALTH_RANGE_INVALID(11060, "The date range is invalid or too long.", HttpStatus.BAD_REQUEST),
    /** A diagnostics run is already running (or today's scheduled run exists). */
    KNOWLEDGE_HEALTH_RUNNING(11061, "A diagnostics run is already running.", HttpStatus.CONFLICT),
    KNOWLEDGE_HOOK_RULE_NOT_FOUND(11062, "Hook rule not found.", HttpStatus.NOT_FOUND),
    KNOWLEDGE_NOTIFICATION_NOT_FOUND(11063, "Notification not found.", HttpStatus.NOT_FOUND);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    QaErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    @Override
    public int getCode() {
        return code;
    }

    @Override
    public String getMessage() {
        return message;
    }

    @Override
    public String getService() {
        return "qa";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
