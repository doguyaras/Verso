package com.verso.document.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the document block, 10000-10999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum DocumentErrorCode implements ErrorCode {

    // --- lookup (10000-10009)
    /** Also for another account's document: existence is not revealed (reference 9, IDOR). */
    DOCUMENT_NOT_FOUND(10001, "Document not found.", HttpStatus.NOT_FOUND),
    // --- upload (10010-10029)
    DOCUMENT_NOT_PDF(10010, "The file is not a PDF document.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_EMPTY(10011, "The file is empty.", HttpStatus.BAD_REQUEST),
    DOCUMENT_TOO_LARGE(10012, "The file is too large.", HttpStatus.CONTENT_TOO_LARGE),
    DOCUMENT_LIMIT_REACHED(10013, "The document limit of the account is reached.", HttpStatus.CONFLICT);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    DocumentErrorCode(int code, String message, HttpStatus httpStatus) {
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
        return "document";
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
