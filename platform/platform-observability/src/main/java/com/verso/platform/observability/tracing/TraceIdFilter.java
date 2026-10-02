package com.verso.platform.observability.tracing;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Puts the request's trace id on every response as X-Trace-Id, including rejections written by later filters
 * (security 401/403) and error envelopes. The header is set before the chain runs because a committed response
 * can no longer take headers.
 */
public class TraceIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = TraceIds.fromMdcOrNew();
        request.setAttribute(TraceIds.REQUEST_ATTRIBUTE, traceId);
        response.setHeader(TraceIds.HEADER, traceId);
        chain.doFilter(request, response);
    }
}
