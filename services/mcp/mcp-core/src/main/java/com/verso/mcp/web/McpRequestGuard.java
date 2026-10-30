package com.verso.mcp.web;

import com.verso.mcp.McpProperties;
import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Semaphore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Runs after authentication on the MCP chain (ADR-0031), so only an authenticated caller gets this far:
 *
 * <ul>
 *   <li><b>Body bound.</b> The transport reads the whole body into a String before parsing; a declared length above
 *       {@code max-body-size} is a 413 at once, and a chunked body is cut off at the same size (the transport then
 *       answers 400).</li>
 *   <li><b>Concurrency bound.</b> At most {@code max-concurrent-calls} MCP requests per instance; the next is a 429
 *       with Retry-After, without waiting. A per-instance bulkhead, not a rate limit: Verso has no shared counter store
 *       (security-rules 3; ADR-0031 lists the gap).</li>
 *   <li><b>One log line per refused request.</b> The transport's and the SDK's own loggers are off (config/verso.yml):
 *       their messages quote parser errors and tool output (llm-rules 2.1). This line has the status only.</li>
 * </ul>
 *
 * Not a bean: Boot would register a Filter bean for every path.
 */
public final class McpRequestGuard extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(McpRequestGuard.class);

    private final long maxBodyBytes;
    private final Semaphore slots;
    private final HandlerExceptionResolver resolver;
    private final MeterRegistry meters;

    public McpRequestGuard(long maxBodyBytes, int maxConcurrentCalls, HandlerExceptionResolver resolver,
                           MeterRegistry meters) {
        this.maxBodyBytes = maxBodyBytes;
        this.slots = new Semaphore(maxConcurrentCalls);
        this.resolver = resolver;
        this.meters = meters;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only calls: a GET (the optional server-sent event stream) is answered 405 at once by the stateless transport.
        // The chain already matches /mcp and the metadata only; the path is not compared as a string, since the
        // transport also answers a spelling with matrix parameters ("/mcp;a=b") that would otherwise pass unbounded.
        return !"POST".equals(request.getMethod()) || request.getRequestURI().contains(McpProperties.METADATA_PATH);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (request.getContentLengthLong() > maxBodyBytes) {
            refuse(request, response, new ServiceException(CommonErrorCode.PAYLOAD_TOO_LARGE, "MCP_BODY_TOO_LARGE"),
                    "body_too_large");
            return;
        }
        if (!slots.tryAcquire()) {
            response.setHeader("Retry-After", "1");
            refuse(request, response, new ServiceException(CommonErrorCode.TOO_MANY_REQUESTS, "MCP_BUSY"), "busy");
            return;
        }
        try {
            chain.doFilter(new Bounded(request, maxBodyBytes), response);
        } finally {
            slots.release();
        }
        int status = response.getStatus();
        if (status >= 500) {
            log.error("MCP request failed: code=MCP_TRANSPORT reason=STATUS_{} method={}", status, request.getMethod());
        } else if (status >= 400) {
            log.warn("MCP request rejected: code=MCP_TRANSPORT reason=STATUS_{} method={}", status, request.getMethod());
        }
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, ServiceException exception,
                        String reason) {
        meters.counter("verso.mcp.requests.refused", "reason", reason).increment();
        resolver.resolveException(request, response, null, exception);
    }

    /** The request with its body cut off after {@code limit} bytes: reading on fails with an IOException. */
    private static final class Bounded extends HttpServletRequestWrapper {

        private final long limit;
        private ServletInputStream stream;

        Bounded(HttpServletRequest request, long limit) {
            super(request);
            this.limit = limit;
        }

        @Override
        public ServletInputStream getInputStream() throws IOException {
            if (stream == null) stream = new LimitedStream(super.getInputStream(), limit);
            return stream;
        }

        @Override
        public BufferedReader getReader() throws IOException {
            String encoding = getCharacterEncoding();
            return new BufferedReader(new InputStreamReader(getInputStream(),
                    encoding == null ? StandardCharsets.UTF_8 : java.nio.charset.Charset.forName(encoding)));
        }
    }

    private static final class LimitedStream extends ServletInputStream {

        private final ServletInputStream in;
        private final long limit;
        private long read;

        LimitedStream(ServletInputStream in, long limit) {
            this.in = in;
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int b = in.read();
            if (b >= 0) count(1);
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = in.read(buffer, offset, length);
            if (n > 0) count(n);
            return n;
        }

        private void count(int n) throws IOException {
            read += n;
            if (read > limit) throw new IOException("MCP request body over the limit");
        }

        @Override
        public boolean isFinished() {
            return in.isFinished();
        }

        @Override
        public boolean isReady() {
            return in.isReady();
        }

        @Override
        public void setReadListener(ReadListener listener) {
            in.setReadListener(listener);
        }
    }
}
