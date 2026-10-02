package com.verso.document.exception;

import com.verso.platform.core.exception.ErrorCode;
import org.springframework.http.HttpStatus;

/** Codes of the document block, 10000-10999 (ADR-0004, ErrorCodeUniquenessTest). */
public enum DocumentErrorCode implements ErrorCode {

    // --- lookup (10000-10009)
    /** Also for another account's document: existence is not revealed (reference 9, IDOR). */
    DOCUMENT_NOT_FOUND(10001, "Document not found.", HttpStatus.NOT_FOUND),
    // --- upload (10010-10029); 10012 was never released (too large is the common 90014)
    DOCUMENT_NOT_PDF(10010, "The file is not a PDF document.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    DOCUMENT_EMPTY(10011, "The file is empty.", HttpStatus.BAD_REQUEST),
    DOCUMENT_LIMIT_REACHED(10013, "The document limit of the account is reached.", HttpStatus.CONFLICT),
    /** Too many uploads are being received at once; each one is held in memory (review E2). */
    DOCUMENT_UPLOADS_BUSY(10014, "Too many uploads in progress, try again shortly.", HttpStatus.SERVICE_UNAVAILABLE),
    /** The account already has verso.document.max-queued-per-account documents waiting (security review S3). */
    DOCUMENT_QUEUE_FULL(10015, "Too many documents are waiting to be processed, try again later.",
            HttpStatus.TOO_MANY_REQUESTS);

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
