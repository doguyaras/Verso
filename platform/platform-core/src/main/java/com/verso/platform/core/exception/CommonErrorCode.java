package com.verso.platform.core.exception;

import org.springframework.http.HttpStatus;

/**
 * Service-independent codes (reference 7.2): validation block 90000-90099, security block 90100-90199, system block
 * 99997-99999.
 * The global handler maps standard MVC failures to these (reference 7.3). Values mirror the reference skeleton so
 * clients see the same codes across projects built from the reference.
 */
public enum CommonErrorCode implements ErrorCode {

    // --- validation: bean validation, binding, malformed body, type mismatch, missing parameter/header (90000-90009)
    VALIDATION(90000, "validation", "Request validation failed.", HttpStatus.BAD_REQUEST),
    REQUEST_NOT_READABLE(90001, "validation", "Request body could not be read.", HttpStatus.BAD_REQUEST),
    TYPE_MISMATCH(90002, "validation", "Request parameter has an invalid type.", HttpStatus.BAD_REQUEST),
    MISSING_PARAMETER(90003, "validation", "Required request parameter is missing.", HttpStatus.BAD_REQUEST),
    /** A 4xx without a more specific mapping (e.g. ResponseStatusException 418); the real status is kept. */
    REQUEST_REJECTED(90004, "validation", "Request was rejected.", HttpStatus.BAD_REQUEST),
    // --- request shape / routing (90010-90019)
    NOT_FOUND(90010, "validation", "Resource not found.", HttpStatus.NOT_FOUND),
    METHOD_NOT_ALLOWED(90011, "validation", "HTTP method not allowed.", HttpStatus.METHOD_NOT_ALLOWED),
    UNSUPPORTED_MEDIA_TYPE(90012, "validation", "Unsupported media type.", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    NOT_ACCEPTABLE(90013, "validation", "Requested media type is not acceptable.", HttpStatus.NOT_ACCEPTABLE),
    PAYLOAD_TOO_LARGE(90014, "validation", "Request payload is too large.", HttpStatus.CONTENT_TOO_LARGE),
    REQUEST_CONFLICT(90015, "validation", "Request conflicts with the current state.", HttpStatus.CONFLICT),
    // --- API versioning (90020-90029): missing or unsupported API-Version (reference 20)
    API_VERSION_INVALID(90020, "validation", "API version is missing or not supported.", HttpStatus.BAD_REQUEST),
    // --- security (90100-90199): identity and authorization rejections; phase 3 adds the security filter chain
    UNAUTHENTICATED(90100, "security", "Authentication is required.", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED(90101, "security", "Access is denied.", HttpStatus.FORBIDDEN),
    TOO_MANY_REQUESTS(90102, "security", "Too many requests.", HttpStatus.TOO_MANY_REQUESTS),
    /** The token could not be checked: the identity provider's keys are unreachable (ADR-0010). */
    IDP_UNAVAILABLE(90103, "security", "Identity provider is unavailable.", HttpStatus.SERVICE_UNAVAILABLE),
    // --- system
    /** Database or pool temporarily unavailable, lock or statement timeout (phase 4); the client retries later. */
    SERVICE_UNAVAILABLE(99997, "system", "Service is temporarily unavailable.", HttpStatus.SERVICE_UNAVAILABLE),
    UPSTREAM_ERROR(99998, "system", "Upstream service failed.", HttpStatus.BAD_GATEWAY),
    INTERNAL_ERROR(99999, "system", "Unexpected error.", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String service;
    private final String message;
    private final HttpStatus httpStatus;

    /**
     * Code for an HTTP status that has no more specific mapping (ResponseStatusException, container error pages).
     * Every 5xx is INTERNAL_ERROR; the response keeps its real status either way.
     */
    public static CommonErrorCode forStatus(int status) {
        if (status >= 500) return INTERNAL_ERROR;
        return switch (status) {
            case 400 -> VALIDATION;
            case 401 -> UNAUTHENTICATED;
            case 403 -> ACCESS_DENIED;
            case 404 -> NOT_FOUND;
            case 405 -> METHOD_NOT_ALLOWED;
            case 406 -> NOT_ACCEPTABLE;
            case 409 -> REQUEST_CONFLICT;
            case 413 -> PAYLOAD_TOO_LARGE;
            case 415 -> UNSUPPORTED_MEDIA_TYPE;
            case 429 -> TOO_MANY_REQUESTS;
            default -> REQUEST_REJECTED;
        };
    }

    CommonErrorCode(int code, String service, String message, HttpStatus httpStatus) {
        this.code = code;
        this.service = service;
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
        return service;
    }

    @Override
    public HttpStatus getHttpStatus() {
        return httpStatus;
    }
}
