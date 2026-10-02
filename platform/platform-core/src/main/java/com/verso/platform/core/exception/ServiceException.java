package com.verso.platform.core.exception;

import java.util.List;

/**
 * Base of every business exception (reference 7.1, 7.4).
 *
 * <ul>
 *   <li>{@code details} goes back to the client: only displayable {@code key=value} pairs.</li>
 *   <li>{@code safeLogReason} / {@code safeLogCategory} are written to the log only, never to the response.</li>
 * </ul>
 * The two are never mixed. A cause's message is not copied into this exception's message, because it may carry
 * personal data or document text.
 */
public class ServiceException extends RuntimeException {

    private final transient ErrorCode errorCode;
    private final List<String> details;
    private final String safeLogReason;
    private final String safeLogCategory;

    public ServiceException(ErrorCode errorCode) {
        this(errorCode, null, null, List.of(), null);
    }

    public ServiceException(ErrorCode errorCode, String safeLogReason) {
        this(errorCode, safeLogReason, null, List.of(), null);
    }

    public ServiceException(ErrorCode errorCode, String safeLogReason, String safeLogCategory, List<String> details) {
        this(errorCode, safeLogReason, safeLogCategory, details, null);
    }

    public ServiceException(ErrorCode errorCode, String safeLogReason, Throwable cause) {
        this(errorCode, safeLogReason, null, List.of(), cause);
    }

    protected ServiceException(ErrorCode errorCode, String safeLogReason, String safeLogCategory,
                               List<String> details, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.errorCode = errorCode;
        this.safeLogReason = safeLogReason;
        this.safeLogCategory = safeLogCategory;
        this.details = details == null ? List.of() : List.copyOf(details);
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public List<String> getDetails() {
        return details;
    }

    public String getSafeLogReason() {
        return safeLogReason;
    }

    public String getSafeLogCategory() {
        return safeLogCategory;
    }
}
