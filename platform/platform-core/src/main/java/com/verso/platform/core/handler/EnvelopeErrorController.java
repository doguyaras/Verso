package com.verso.platform.core.handler;

import com.verso.platform.core.dto.ApiResponse;
import com.verso.platform.core.dto.ErrorResponse;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ErrorCode;
import com.verso.platform.core.exception.ServiceException;
import com.verso.platform.observability.logging.SensitiveLogSanitizer;
import com.verso.platform.observability.tracing.TraceIds;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Replaces Boot's BasicErrorController so that errors raised outside Spring MVC (servlet filters, the container's
 * error dispatch, a direct call to /error) use the same envelope and X-Trace-Id as everything else (reference 6.2,
 * 7.6). It never echoes the exception message or the container's reason phrase, and logs types only.
 *
 * <p>The exception forwarded by the container decides the answer the same way the global handler would: a
 * ServiceException keeps its own status and code (phase 3 security filters rely on this), an unparsable request is a
 * 400, anything else a 500. A direct request to /error has no error status and is answered as 404, not as a 500 that
 * would inflate the error rate.
 */
@Controller
@RequestMapping("${server.error.path:${error.path:/error}}")
public class EnvelopeErrorController implements ErrorController {

    private static final Logger log = LoggerFactory.getLogger(EnvelopeErrorController.class);

    private final Clock clock;

    public EnvelopeErrorController(Clock clock) {
        this.clock = clock;
    }

    @RequestMapping
    public ResponseEntity<ApiResponse<Void>> error(HttpServletRequest request, HttpServletResponse response) {
        String traceId = TraceIds.current(request);
        Throwable exception = exception(request);
        HttpStatus status;
        ErrorCode code;
        List<String> details = List.of();
        if (exception instanceof ServiceException se) {
            code = se.getErrorCode();
            status = code.getHttpStatus();
            details = se.getDetails();
            logOutcome(status, code, se.getClass().getSimpleName(), "none", se.getSafeLogReason(), traceId);
        } else if (exception != null && ErrorClassifier.isMalformedRequest(exception)) {
            code = CommonErrorCode.REQUEST_NOT_READABLE;
            status = HttpStatus.BAD_REQUEST;
            logOutcome(status, code, exception.getClass().getSimpleName(), "none", null, traceId);
        } else {
            status = HttpStatus.valueOf(status(request, exception));
            code = CommonErrorCode.forStatus(status.value());
            logOutcome(status, code, exception == null ? "none" : exception.getClass().getSimpleName(),
                    exception == null ? "none" : SensitiveLogSanitizer.rootCauseType(exception), null, traceId);
        }
        if (response.isCommitted()) {
            // The client already has a status and part of a body: the failure is logged above, nothing is appended.
            return null;
        }
        Object originalUri = request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI);
        String path = originalUri instanceof String s ? s : request.getRequestURI();
        ErrorResponse error = new ErrorResponse(code.getCode(), code.getMessage(), code.getService(), path,
                clock.millis(), traceId, details);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .header(TraceIds.HEADER, traceId)
                .body(ApiResponse.error(error));
    }

    private static void logOutcome(HttpStatus status, ErrorCode code, String exceptionType, String rootCauseType,
                                   String reason, String traceId) {
        String name = code instanceof Enum<?> e ? e.name() : String.valueOf(code.getCode());
        if (status.is5xxServerError()) {
            log.error("Request failed: code={} status={} reason={} exceptionType={} rootCauseType={} traceId={}",
                    name, status.value(), reason, exceptionType, rootCauseType, traceId);
        } else {
            log.warn("Request rejected: code={} status={} reason={} exceptionType={} traceId={}",
                    name, status.value(), reason, exceptionType, traceId);
        }
    }

    private static Throwable exception(HttpServletRequest request) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_EXCEPTION);
        return value instanceof Throwable t ? ErrorClassifier.unwrapServlet(t) : null;
    }

    /**
     * The container's error status, if it is one. An exception without an error status (the response was committed
     * as 200 before a filter failed) is a server failure, not a 404: it must reach alerting (third-round review B25).
     */
    private static int status(HttpServletRequest request, Throwable exception) {
        Object value = request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE);
        if (value instanceof Integer i && i >= 400 && HttpStatus.resolve(i) != null) return i;
        return exception != null ? 500 : 404;
    }
}
