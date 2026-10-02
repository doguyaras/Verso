package com.verso.platform.security.web;

import com.verso.platform.core.exception.CommonErrorCode;
import com.verso.platform.core.exception.ServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Spring Security's firewall refuses non-normalised paths ("/../", "//", encoded slashes, ...) before any other
 * filter. This answers 400 REQUEST_REJECTED in the common envelope; the path and the firewall's message, which quotes
 * the offending input, are never written (reference 8.4, phase 1 review B2).
 */
public final class EnvelopeRequestRejectedHandler implements RequestRejectedHandler {

    private final HandlerExceptionResolver resolver;

    public EnvelopeRequestRejectedHandler(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, RequestRejectedException rejected) {
        resolver.resolveException(request, response, null,
                new ServiceException(CommonErrorCode.REQUEST_REJECTED, "FIREWALL"));
    }
}
