package com.verso.qa.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the qa block, 11000-11999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum QaErrorCode implements ErrorCode {

    // --- model (11000-11009); fail closed: never an empty or made-up answer (ADR-0008, llm-rules 2.3)
    MODEL_UNAVAILABLE(11001, "The language model is unavailable, try again later.", HttpStatus.SERVICE_UNAVAILABLE),
    MODEL_BUSY(11002, "The language model is busy, try again shortly.", HttpStatus.SERVICE_UNAVAILABLE),
    /** ADR-0056: every model of the route is over its hard monthly budget (connection or the caller's team). */
    MODEL_BUDGET_EXCEEDED(11003, "The monthly model budget is used up; contact your administrator.",
            HttpStatus.TOO_MANY_REQUESTS),
    /** ADR-0056: the question names a model the admin does not offer (or user choice is off). */
    MODEL_CHOICE_INVALID(11004, "This model cannot be chosen.", HttpStatus.BAD_REQUEST),

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
    KNOWLEDGE_NOTIFICATION_NOT_FOUND(11063, "Notification not found.", HttpStatus.NOT_FOUND),

    // --- skills / procedures (11070-11079, ADR-0053); an unknown or foreign id is "not found"
    SKILL_NOT_FOUND(11070, "Procedure not found.", HttpStatus.NOT_FOUND),
    /** The transition is not allowed in the skill's state (or another admin changed it first). */
    SKILL_STATE_CONFLICT(11071, "The procedure's state does not allow this.", HttpStatus.CONFLICT),
    /** No verified card or sourced answer the admin can read now (or a source is personal): nothing to draft from. */
    SKILL_NO_SOURCES(11072, "There are no verified sources to draft this procedure from.", HttpStatus.CONFLICT),
    /** A bound source is gone, changed or no longer readable by the admin: a new draft is needed. */
    SKILL_SOURCE_CHANGED(11073, "A source of this procedure has changed or is not readable.", HttpStatus.CONFLICT),
    /** The draft or version breaks a rule (step positions, golden case page, 2-5 golden cases for approval). */
    SKILL_INVALID(11074, "The procedure is not complete or not valid.", HttpStatus.BAD_REQUEST),
    /** A step states a number or a code its sources do not give (ADR-0047): it must be fixed before approval. */
    SKILL_STEP_UNGROUNDED(11075, "A step is not supported by its sources.", HttpStatus.CONFLICT),
    /** The two-person rule: the second approval must come from another admin. */
    SKILL_ALREADY_APPROVED_BY_YOU(11076, "You have already approved this procedure.", HttpStatus.CONFLICT),
    /** Another live procedure has this name. */
    SKILL_NAME_TAKEN(11077, "A procedure with this name already exists.", HttpStatus.CONFLICT),
    /** The diagnostics cluster is unknown (or its run was deleted by the retention). */
    SKILL_CLUSTER_NOT_FOUND(11078, "Question cluster not found.", HttpStatus.NOT_FOUND),
    /** Only an approved procedure can be exported. */
    SKILL_NOT_APPROVED(11079, "Only an approved procedure can be exported.", HttpStatus.CONFLICT),
    // --- system model suggestions (11080-11089, ADR-0054)
    /** The optional local-model suggestion pass is off (VERSO_QA_SYSTEM_MODEL_SUGGESTIONS_ENABLED) or not local. */
    SYSTEM_SUGGESTIONS_DISABLED(11080, "Relation suggestions are turned off.", HttpStatus.CONFLICT),
    /** Unknown and unreadable alike. */
    SYSTEM_SUGGESTION_NOT_FOUND(11081, "Suggestion not found.", HttpStatus.NOT_FOUND),
    /** Only a DRAFT suggestion can be accepted or rejected. */
    SYSTEM_SUGGESTION_NOT_DRAFT(11082, "The suggestion was already reviewed.", HttpStatus.CONFLICT),
    /** The document is unknown, not READY or not readable by the admin. */
    SYSTEM_SUGGESTION_DOCUMENT_NOT_FOUND(11083, "Document not found.", HttpStatus.NOT_FOUND),

    // --- model gateway (11090-11099, ADR-0056); never the provider's message, never the key
    MODEL_CONNECTION_NOT_FOUND(11090, "Model connection not found.", HttpStatus.NOT_FOUND),
    /** Local mode (llm-rules 1.1): a cloud provider, or an address outside the internal network, cannot be enabled. */
    MODEL_CONNECTION_LOCAL_MODE(11091, "Local mode allows only model servers on the internal network.",
            HttpStatus.CONFLICT),
    /** A field the type needs is missing or malformed (base URL, region, deployment, project, key format). */
    MODEL_CONNECTION_INVALID(11092, "The connection settings are not valid for this provider.", HttpStatus.BAD_REQUEST),
    MODEL_CONNECTION_NAME_TAKEN(11093, "A connection with this name already exists.", HttpStatus.CONFLICT),
    /** The key-encryption key (SECRET_MODEL_GATEWAY_KEK) is not installed: no key can be stored or read. */
    MODEL_KEY_STORE_UNAVAILABLE(11094, "The key store is not set up on this installation.",
            HttpStatus.SERVICE_UNAVAILABLE),
    /** The validation call on save or rotation failed (bad key, unknown model, unreachable). */
    MODEL_CONNECTION_CHECK_FAILED(11095, "The provider did not accept the connection.", HttpStatus.UNPROCESSABLE_CONTENT),
    /** Unknown or disabled connection, a model it does not list, an embedding route, or a cloud model in local mode. */
    MODEL_ROUTE_INVALID(11096, "The route is not valid.", HttpStatus.BAD_REQUEST),
    /** A route still uses the connection: change the route first. */
    MODEL_CONNECTION_IN_USE(11097, "The connection is used by a route.", HttpStatus.CONFLICT),
    /** A price or budget names an unknown connection or model. */
    MODEL_PRICE_INVALID(11098, "The price or budget is not valid.", HttpStatus.BAD_REQUEST),
    /** The month is not YYYY-MM or out of range. */
    MODEL_USAGE_RANGE_INVALID(11099, "The month is invalid.", HttpStatus.BAD_REQUEST),
    // --- activity centre (11110-11119, ADR-0058); 11090-11099 are the model gateway's (ADR-0056)
    /** An unknown type, status or category word, a reversed or too long window, or a page beyond the cap. */
    ACTIVITY_FILTER_INVALID(11110, "The activity filter is invalid.", HttpStatus.BAD_REQUEST),
    /** An id of an unknown source, a malformed id or an entry the source does not know (or the admin may not read). */
    ACTIVITY_NOT_FOUND(11111, "Activity entry not found.", HttpStatus.NOT_FOUND);

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
