package com.verso.platform.security.web;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 401 in the common envelope (reference 7.6): the failure becomes a ServiceException and goes through the global
 * handler, so the body, the code (UNAUTHENTICATED, 90100), the trace id and the types-only log line are the same as
 * everywhere else. The token, its claims and the validator's message never reach the body or the log; the reason
 * is a fixed word.
 *
 * <p>A token that could not be checked because the IdP's keys are unreachable is not the caller's fault: 503
 * IDP_UNAVAILABLE with Retry-After, no WWW-Authenticate (phase 3 resilience review R2).
 */
public final class EnvelopeAuthenticationEntryPoint implements AuthenticationEntryPoint {

    /** Seconds; the key source retries the IdP on the next request after its refetch interval. */
    static final String RETRY_AFTER = "30";

    private final HandlerExceptionResolver resolver;

    public EnvelopeAuthenticationEntryPoint(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
        if (failure instanceof AuthenticationServiceException) {
            response.setHeader("Retry-After", RETRY_AFTER);
            resolver.resolveException(request, response, null,
                    new ServiceException(CommonErrorCode.IDP_UNAVAILABLE, "IDP_UNAVAILABLE"));
            return;
        }
        boolean invalidToken = failure instanceof OAuth2AuthenticationException;
        // RFC 6750 3.1: a request without credentials gets no error code; an invalid token gets invalid_token.
        response.setHeader("WWW-Authenticate", invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        resolver.resolveException(request, response, null,
                new ServiceException(CommonErrorCode.UNAUTHENTICATED, invalidToken ? "INVALID_TOKEN" : "NO_TOKEN"));
    }
}
