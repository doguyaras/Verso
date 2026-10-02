package com.verso.document.controller;

import com.verso.document.config.DocumentProperties;
import com.verso.document.exception.DocumentErrorCode;
import com.verso.document.exception.DocumentServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.concurrent.Semaphore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bounds the uploads received at once (phase 4 review E2): each is held in memory, twice (the multipart part and its
 * bytes), and virtual threads would otherwise accept any number of them. Runs before the multipart body is read
 * (spring.servlet.multipart.resolve-lazily); a full limiter answers 503 DOCUMENT_UPLOADS_BUSY with Retry-After.
 */
@Component
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UploadLimiter implements HandlerInterceptor, WebMvcConfigurer {

    static final String RETRY_AFTER_SECONDS = "5";
    private static final String PERMIT = UploadLimiter.class.getName() + ".permit";

    private final Semaphore permits;

    public UploadLimiter(DocumentProperties properties) {
        this.permits = new Semaphore(properties.maxConcurrentUploads());
    }

    /** Registers itself for the upload path only (here, not in config: no config ↔ controller package cycle). */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/documents");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        if (!permits.tryAcquire()) {
            response.setHeader(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
            throw new DocumentServiceException(DocumentErrorCode.DOCUMENT_UPLOADS_BUSY);
        }
        request.setAttribute(PERMIT, Boolean.TRUE);
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (request.getAttribute(PERMIT) != null) {
            request.removeAttribute(PERMIT);
            permits.release();
        }
    }

    /** Uploads in progress right now; for tests and diagnostics. */
    public int inProgress(DocumentProperties properties) {
        return properties.maxConcurrentUploads() - permits.availablePermits();
    }
}
