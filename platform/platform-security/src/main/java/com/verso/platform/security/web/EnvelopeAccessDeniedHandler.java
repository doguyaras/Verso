package com.verso.platform.security.web;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/** 403 in the common envelope (ACCESS_DENIED, 90101), through the global handler like every other error. */
public final class EnvelopeAccessDeniedHandler implements AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    public EnvelopeAccessDeniedHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException denied) {
        response.setHeader("WWW-Authenticate", "Bearer error=\"insufficient_scope\"");
        resolver.resolveException(request, response, null,
                new ServiceException(CommonErrorCode.ACCESS_DENIED, "ACCESS_DENIED"));
    }
}
