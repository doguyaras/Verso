package com.verso.audit.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the audit block, 12000-12999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum AuditErrorCode implements ErrorCode {

    // --- query (12000-12009, ADR-0026)
    AUDIT_RANGE_INVALID(12001, "The date range is invalid or too long.", HttpStatus.BAD_REQUEST);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    AuditErrorCode(int code, String message, HttpStatus httpStatus) {
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
        return "audit";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
