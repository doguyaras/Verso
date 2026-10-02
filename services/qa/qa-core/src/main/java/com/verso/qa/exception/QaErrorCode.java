package com.verso.qa.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the qa block, 11000-11999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum QaErrorCode implements ErrorCode {

    // --- model (11000-11009); fail closed: never an empty or made-up answer (ADR-0008, llm-rules 2.3)
    MODEL_UNAVAILABLE(11001, "The language model is unavailable, try again later.", HttpStatus.SERVICE_UNAVAILABLE),
    MODEL_BUSY(11002, "The language model is busy, try again shortly.", HttpStatus.SERVICE_UNAVAILABLE);

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
