package com.verso.platform.core.handler;

import com.verso.platform.core.dto.ApiResponse;
import com.verso.platform.core.dto.ErrorResponse;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.observability.logging.SensitiveLogSanitizer;
import com.verso.platform.observability.tracing.TraceIds;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.accept.InvalidApiVersionException;
import org.springframework.web.accept.MissingApiVersionException;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.ServletWebRequest;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Global handler (reference 7.3). Extends ResponseEntityExceptionHandler so that every standard MVC failure
 * (400/404/405/406/413/415, API version 400) passes through handleExceptionInternal; the code mapping and the single
 * envelope are produced there. A new MVC failure type therefore never falls through to 500.
 *
 * <p>Privacy: neither the response nor the log ever contains a rejected value, an exception message or a request
 * body. On 500 only {@code exceptionType} and a sanitized summary are logged; the raw throwable is not attached,
 * because its message chain may carry personal data or document text (reference 8.4).
 *
 * <p>Ported from the reference skeleton; the log-only reason now lives on {@link ServiceException} itself, as
 * reference 7.1 specifies, instead of on a per-service subclass.
 */
@RestControllerAdvice
public class GlobalServiceExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalServiceExceptionHandler.class);
    /** Seconds a client should wait after a 503: a pool or lock wait, not a long outage. */
    static final String RETRY_AFTER_SECONDS = "5";

    private final Clock clock;

    public GlobalServiceExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    /** Business failures: status and code come from the exception; reason goes to the log, details to the client. */
    @ExceptionHandler(ServiceException.class)
    public ResponseEntity<Object> handleServiceException(ServiceException ex, WebRequest request) {
        ErrorCode code = ex.getErrorCode();
        HttpStatus status = code.getHttpStatus();
        String traceId = traceId(request);
        if (status.is5xxServerError()) {
            log.error("Request failed: code={} status={} reason={} category={} exceptionType={} traceId={}",
                    name(code), status.value(), ex.getSafeLogReason(), ex.getSafeLogCategory(),
                    ex.getClass().getSimpleName(), traceId);
        } else {
            log.warn("Request rejected: code={} status={} reason={} category={} traceId={}",
                    name(code), status.value(), ex.getSafeLogReason(), ex.getSafeLogCategory(), traceId);
        }
        return envelope(status, code, ex.getDetails(), traceId, request, null);
    }

    /** Constraint violations on @Validated method parameters are not covered by the base class. */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<String> details = ex.getConstraintViolations().stream()
                .map(v -> leafName(v) + "=" + ruleMessage(v))
                .sorted()
                .toList();
        String traceId = traceId(request);
        log.warn("Request rejected: code={} status=400 exceptionType={} traceId={}",
                name(CommonErrorCode.VALIDATION), ex.getClass().getSimpleName(), traceId);
        return envelope(HttpStatus.BAD_REQUEST, CommonErrorCode.VALIDATION, details, traceId, request, null);
    }

    /**
     * Anything else: 500 with a generic message. The log gets the exception type and the root-cause type only; the
     * message is never logged, not even sanitized, because it may carry document text, file names or hosts that no
     * pattern can recognise (llm-rules 2.1; stricter than reference 7.3, see ADR-0007).
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) throws Exception {
        // Spring Security's denial and authentication exceptions belong to its ExceptionTranslationFilter (401/403 in
        // the envelope, platform-security); swallowing them here turned a @PreAuthorize denial into a 500 (phase 3).
        // Re-throwing the same instance is not logged by Spring as a failing handler.
        if (ErrorClassifier.isSecurityException(ex)) throw ex;
        String traceId = traceId(request);
        // Undecodable parameters (Tomcat) and broken multipart bodies arrive here, not through the base class.
        if (ErrorClassifier.isMalformedRequest(ex)) {
            log.warn("Request rejected: code={} status=400 exceptionType={} traceId={}",
                    name(CommonErrorCode.REQUEST_NOT_READABLE), ex.getClass().getSimpleName(), traceId);
            return envelope(HttpStatus.BAD_REQUEST, CommonErrorCode.REQUEST_NOT_READABLE, List.of(), traceId, request,
                    null);
        }
        // Database unreachable, pool exhausted, lock or statement timeout: a temporary condition of the server, not a
        // bug. 503 with Retry-After tells the client to come back (phase 4 reviews C4/R4/D2; repo-context section 3).
        if (ErrorClassifier.isTemporarilyUnavailable(ex)) {
            log.error("Request failed: code={} status=503 exceptionType={} rootCauseType={} traceId={}",
                    name(CommonErrorCode.SERVICE_UNAVAILABLE), ex.getClass().getSimpleName(),
                    SensitiveLogSanitizer.rootCauseType(ex), traceId);
            HttpHeaders headers = new HttpHeaders();
            headers.set(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            return envelope(HttpStatus.SERVICE_UNAVAILABLE, CommonErrorCode.SERVICE_UNAVAILABLE, List.of(), traceId,
                    request, headers);
        }
        log.error("Request failed: code={} status=500 exceptionType={} rootCauseType={} traceId={}",
                name(CommonErrorCode.INTERNAL_ERROR), ex.getClass().getSimpleName(),
                SensitiveLogSanitizer.rootCauseType(ex), traceId);
        return envelope(HttpStatus.INTERNAL_SERVER_ERROR, CommonErrorCode.INTERNAL_ERROR, List.of(), traceId, request,
                null);
    }

    /**
     * Every handleXxx method of ResponseEntityExceptionHandler ends here (the status is the base class's decision).
     * Headers prepared by the base class (Allow on 405, Accept on 415) are kept. An unmapped 4xx gets the code that
     * matches its status, a 5xx INTERNAL_ERROR; neither carries raw text.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
                                                             HttpStatusCode status, WebRequest request) {
        String traceId = traceId(request);
        if (status.is5xxServerError()) {
            log.error("Request failed: code={} status={} exceptionType={} rootCauseType={} traceId={}",
                    name(CommonErrorCode.INTERNAL_ERROR), status.value(), ex.getClass().getSimpleName(),
                    SensitiveLogSanitizer.rootCauseType(ex), traceId);
            return envelope(status, CommonErrorCode.INTERNAL_ERROR, List.of(), traceId, request, headers);
        }
        Mapping m = map(ex, status);
        log.warn("Request rejected: code={} status={} exceptionType={} traceId={}",
                name(m.code()), status.value(), ex.getClass().getSimpleName(), traceId);
        return envelope(status, m.code(), m.details(), traceId, request, headers);
    }

    private record Mapping(CommonErrorCode code, List<String> details) {}

    private static Mapping map(Exception ex, HttpStatusCode status) {
        return switch (ex) {
            case MethodArgumentNotValidException e -> new Mapping(CommonErrorCode.VALIDATION, fieldDetails(e));
            case HandlerMethodValidationException e -> new Mapping(CommonErrorCode.VALIDATION, parameterDetails(e));
            case HttpMessageNotReadableException e -> new Mapping(CommonErrorCode.REQUEST_NOT_READABLE, List.of());
            case MethodArgumentTypeMismatchException e ->
                    new Mapping(CommonErrorCode.TYPE_MISMATCH, List.of("parameter=" + e.getName()));
            case TypeMismatchException e -> new Mapping(CommonErrorCode.TYPE_MISMATCH, List.of());
            case MissingServletRequestParameterException e ->
                    new Mapping(CommonErrorCode.MISSING_PARAMETER, List.of("parameter=" + e.getParameterName()));
            case MissingServletRequestPartException e ->
                    new Mapping(CommonErrorCode.MISSING_PARAMETER, List.of("part=" + e.getRequestPartName()));
            case MissingRequestHeaderException e ->
                    new Mapping(CommonErrorCode.MISSING_PARAMETER, List.of("header=" + e.getHeaderName()));
            case ServletRequestBindingException e -> new Mapping(CommonErrorCode.MISSING_PARAMETER, List.of());
            case NoResourceFoundException e -> new Mapping(CommonErrorCode.NOT_FOUND, List.of());
            case NoHandlerFoundException e -> new Mapping(CommonErrorCode.NOT_FOUND, List.of());
            case HttpRequestMethodNotSupportedException e -> new Mapping(CommonErrorCode.METHOD_NOT_ALLOWED,
                    e.getSupportedMethods() == null ? List.of()
                            : List.of("allowed=" + String.join(",", e.getSupportedMethods())));
            case HttpMediaTypeNotSupportedException e -> new Mapping(CommonErrorCode.UNSUPPORTED_MEDIA_TYPE,
                    List.of("supported=" + MediaType.toString(e.getSupportedMediaTypes())));
            case HttpMediaTypeNotAcceptableException e -> new Mapping(CommonErrorCode.NOT_ACCEPTABLE, List.of());
            case MaxUploadSizeExceededException e -> new Mapping(CommonErrorCode.PAYLOAD_TOO_LARGE, List.of());
            case MissingApiVersionException e -> new Mapping(CommonErrorCode.API_VERSION_INVALID, List.of());
            case InvalidApiVersionException e -> new Mapping(CommonErrorCode.API_VERSION_INVALID, List.of());
            default -> new Mapping(CommonErrorCode.forStatus(status.value()), List.of());
        };
    }

    /**
     * Field name + rule message (e.g. question=must not be blank). Binding failures (type conversion) never use the
     * framework message, which quotes the rejected value and internal type names. Rule messages are judged by their
     * template, not by searching the rendered text (second-round review): see {@link #ruleMessage}.
     */
    private static List<String> fieldDetails(MethodArgumentNotValidException e) {
        List<String> details = new ArrayList<>();
        e.getBindingResult().getFieldErrors().forEach(f -> details.add(fieldPath(f) + "="
                + (f.isBindingFailure() ? INVALID : ruleMessage(f))));
        e.getBindingResult().getGlobalErrors().forEach(g -> details.add(g.getObjectName() + "=" + ruleMessage(g)));
        return details;
    }

    private static List<String> parameterDetails(HandlerMethodValidationException e) {
        List<String> details = new ArrayList<>();
        e.getParameterValidationResults().forEach(r -> {
            String name = r.getMethodParameter().getParameterName();
            String message = r.getResolvableErrors().isEmpty() ? INVALID
                    : ruleMessage(r, r.getResolvableErrors().get(0));
            details.add((name != null ? name : "arg" + r.getMethodParameter().getParameterIndex()) + "=" + message);
        });
        return details;
    }

    private static final String INVALID = "invalid";

    /**
     * Expression Language in a constraint message ({@code ${validatedValue}}, {@code ${formatter.format(...)}}) can
     * render the rejected value or any part of the object, so such a message is never shown. Plain message parameters
     * ({@code {min}}, {@code {max}}) only render annotation attributes and are safe.
     */
    private static String ruleMessage(ConstraintViolation<?> violation) {
        String template = violation.getMessageTemplate();
        if (template == null || template.contains("${")) return INVALID;
        String message = violation.getMessage();
        return message == null || message.isBlank() ? INVALID : message;
    }

    private static String ruleMessage(ObjectError error) {
        return error.contains(ConstraintViolation.class) ? ruleMessage(error.unwrap(ConstraintViolation.class))
                : fallbackMessage();
    }

    private static String ruleMessage(ParameterValidationResult result, MessageSourceResolvable error) {
        try {
            return ruleMessage(result.unwrap(error, ConstraintViolation.class));
        } catch (IllegalArgumentException notAConstraintViolation) {
            return fallbackMessage();
        }
    }

    /** Errors that do not come from Bean Validation (custom Validator) carry no template: only a generic text. */
    private static String fallbackMessage() {
        return INVALID;
    }

    /**
     * Map keys and indexes come from the client (attrs[jane.doe@example.com]); only the property path is kept. It is
     * built from the Bean Validation path nodes, never by parsing the field string: a key may itself contain "]" or
     * "[" (third-round review B21). Without a violation (type mismatch) everything from the first "[" is dropped.
     */
    private static String fieldPath(FieldError error) {
        if (error.contains(ConstraintViolation.class)) {
            String path = propertyPath(error.unwrap(ConstraintViolation.class));
            if (!path.isEmpty()) return path;
        }
        String field = error.getField();
        int bracket = field.indexOf('[');
        return bracket < 0 ? field : field.substring(0, bracket) + "[]";
    }

    private static String propertyPath(ConstraintViolation<?> violation) {
        StringBuilder path = new StringBuilder();
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.isInIterable()) path.append("[]");
            String name = node.getName();
            // Container element nodes are named "<map value>", "<list element>": not part of the visible path.
            if (name == null || name.startsWith("<")) continue;
            if (!path.isEmpty()) path.append('.');
            path.append(name);
        }
        return path.toString();
    }

    /** The leaf property name only: the full path would expose method and parameter names (create.arg0.email). */
    private static String leafName(ConstraintViolation<?> violation) {
        String leaf = null;
        for (Path.Node node : violation.getPropertyPath()) {
            if (node.getName() != null) leaf = node.getName();
        }
        return leaf == null ? "value" : leaf;
    }

    private ResponseEntity<Object> envelope(HttpStatusCode status, ErrorCode code, List<String> details,
                                            String traceId, WebRequest request, HttpHeaders prepared) {
        HttpServletRequest servletRequest = servletRequest(request);
        String path = servletRequest == null ? null : servletRequest.getRequestURI();
        ErrorResponse error = new ErrorResponse(code.getCode(), code.getMessage(), code.getService(), path,
                clock.millis(), traceId, details);
        HttpHeaders headers = new HttpHeaders();
        if (prepared != null) {
            headers.putAll(prepared);
            headers.remove(HttpHeaders.CONTENT_TYPE);
            headers.remove(HttpHeaders.CONTENT_LENGTH);
        }
        // Same value TraceIdFilter already set; Spring overwrites (does not append) the header, verified with
        // MockMvc and Tomcat. Setting it here also covers requests that never passed through the filter.
        headers.set(TraceIds.HEADER, traceId);
        return ResponseEntity.status(status)
                .headers(headers)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiResponse.error(error));
    }

    private static String traceId(WebRequest request) {
        return TraceIds.current(servletRequest(request));
    }

    private static HttpServletRequest servletRequest(WebRequest request) {
        return request instanceof ServletWebRequest s ? s.getRequest() : null;
    }

    private static String name(ErrorCode code) {
        return code instanceof Enum<?> e ? e.name() : String.valueOf(code.getCode());
    }
}
