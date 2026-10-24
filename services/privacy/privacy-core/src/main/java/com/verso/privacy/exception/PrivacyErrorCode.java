package com.verso.privacy.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the privacy block, 13000-13999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum PrivacyErrorCode implements ErrorCode {

    // --- data-subject requests (13000-13019, ADR-0034)
    SUBJECT_INVALID(13001, "The subject is not a valid token subject.", HttpStatus.BAD_REQUEST),
    SUBJECT_CONFIRMATION_MISMATCH(13002, "The confirmation does not repeat the subject of the path.",
            HttpStatus.BAD_REQUEST),

    // --- answer redaction (13020-13039, ADR-0034)
    REDACTION_CONFIRMATION_MISMATCH(13020, "The confirmation does not repeat the document of the path.",
            HttpStatus.BAD_REQUEST),
    DOCUMENT_NOT_DELETED(13021, "The document still exists; delete it before redacting the answers that cite it.",
            HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    PrivacyErrorCode(int code, String message, HttpStatus httpStatus) {
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
        return "privacy";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
