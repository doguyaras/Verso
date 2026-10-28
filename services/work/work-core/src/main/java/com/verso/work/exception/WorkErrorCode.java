package com.verso.work.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the work block, 15000-15999 (ADR-0004, ADR-0057, ErrorCodeUniquenessTest). */
public enum WorkErrorCode implements ErrorCode {

    SKILL_NOT_FOUND(15001, "Skill not found.", HttpStatus.NOT_FOUND),
    SKILL_DISABLED(15002, "This skill is switched off in this installation.", HttpStatus.NOT_FOUND),
    INPUT_INVALID(15003, "The form values do not fit the skill.", HttpStatus.BAD_REQUEST),
    RUN_NOT_FOUND(15004, "Run not found.", HttpStatus.NOT_FOUND),
    RUN_NOT_FINISHED(15005, "The run has no finished output.", HttpStatus.CONFLICT),
    OUTPUT_INVALID(15006, "The output does not fit the skill's output schema.", HttpStatus.BAD_REQUEST),
    DEFINITION_INVALID(15007, "The skill definition is invalid.", HttpStatus.BAD_REQUEST),
    CUSTOM_SKILL_NOT_FOUND(15008, "Custom skill not found.", HttpStatus.NOT_FOUND),
    SKILL_ID_TAKEN(15009, "A skill with this id or slash command exists.", HttpStatus.CONFLICT),
    CUSTOM_SKILL_STATE(15010, "The custom skill is not in a state that allows this.", HttpStatus.CONFLICT),
    RUN_ACTIVE(15011, "A run of yours is still running.", HttpStatus.CONFLICT),
    MODEL_BUSY(15012, "The model is busy; try again shortly.", HttpStatus.SERVICE_UNAVAILABLE),
    PAGE_INVALID(15013, "The page or size is out of range.", HttpStatus.BAD_REQUEST),
    EXPORT_FORMAT_INVALID(15014, "Unknown export format.", HttpStatus.BAD_REQUEST),
    BODY_TOO_LARGE(15015, "The request body is too large.", HttpStatus.CONTENT_TOO_LARGE),
    LENGTH_REQUIRED(15016, "The request body needs a Content-Length.", HttpStatus.LENGTH_REQUIRED),
    STREAMS_BUSY(15017, "Too many progress streams are open; poll the run instead.", HttpStatus.SERVICE_UNAVAILABLE);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    WorkErrorCode(int code, String message, HttpStatus httpStatus) {
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
        return "work";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
