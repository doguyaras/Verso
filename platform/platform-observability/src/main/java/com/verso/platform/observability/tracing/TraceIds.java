package com.verso.platform.observability.tracing;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;

/**
 * One trace id per request, shared by the response header, the error envelope and the log lines (reference 5.4, 7.3).
 *
 * <p>Source order: the id Micrometer Tracing put into the MDC (once tracing is enabled), otherwise a generated one.
 * A client-supplied X-Trace-Id is never trusted: a trace id is not an authorization signal (reference 8.6) and
 * accepting it would let a caller forge correlation in our logs.
 */
public final class TraceIds {

    public static final String HEADER = "X-Trace-Id";
    /** Request attribute written by {@link TraceIdFilter}; readers use {@link #current(HttpServletRequest)}. */
    public static final String REQUEST_ATTRIBUTE = "x.traceId";

    private static final String MDC_KEY = "traceId";
    private static final Pattern W3C_TRACE_ID = Pattern.compile("[0-9a-f]{32}");

    private TraceIds() {}

    /** The id of the current request; generates (but does not store) one when no filter ran, e.g. in slice tests. */
    public static String current(HttpServletRequest request) {
        Object stored = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        if (stored instanceof String s && !s.isBlank()) return s;
        return fromMdcOrNew();
    }

    static String fromMdcOrNew() {
        String fromSpan = MDC.get(MDC_KEY);
        if (fromSpan != null && W3C_TRACE_ID.matcher(fromSpan).matches()) return fromSpan;
        return UUID.randomUUID().toString().replace("-", "");
    }
}
