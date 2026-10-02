package com.verso.platform.security.web;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * 401 in the common envelope (reference 7.6): the failure becomes a ServiceException and goes through the global
 * handler, so the body, the code (UNAUTHENTICATED, 90100), the trace id and the types-only log line are the same as
 * everywhere else. The token, its claims and the validator's message never reach the body or the log; the reason
 * is a fixed word.
 */
public final class EnvelopeAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final HandlerExceptionResolver resolver;

    public EnvelopeAuthenticationEntryPoint(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response, AuthenticationException failure) {
        boolean invalidToken = failure instanceof OAuth2AuthenticationException || failure instanceof InvalidBearerTokenException;
        // RFC 6750 3.1: a request without credentials gets no error code; an invalid token gets invalid_token.
        response.setHeader("WWW-Authenticate", invalidToken ? "Bearer error=\"invalid_token\"" : "Bearer");
        resolver.resolveException(request, response, null,
                new ServiceException(CommonErrorCode.UNAUTHENTICATED, invalidToken ? "INVALID_TOKEN" : "NO_TOKEN"));
    }
}
