package com.verso.work.controller;

import com.verso.work.exception.WorkErrorCode;
import com.verso.work.exception.WorkServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bounds the JSON bodies of the skill library before Jackson reads them (same rule as qa's QuestionBodyLimit): the
 * largest legitimate body is a form of at most ten fields, one long text of 30 000 characters, or a definition, far
 * below 1 MB; without the bound Jackson would parse up to the servlet limit into the heap before validation says 400. A
 * body without Content-Length (chunked) cannot be bounded here and is refused with 411.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class WorkBodyLimit implements HandlerInterceptor, WebMvcConfigurer {

    static final long MAX_BODY_BYTES = 1024 * 1024;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/skills/**", "/v1/skill-runs/**", "/v1/admin/task-skills/**",
                "/v1/admin/custom-skills/**", "/v1/admin/custom-skills");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String method = request.getMethod();
        if (!"POST".equals(method) && !"PUT".equals(method)) return true;
        long length = request.getContentLengthLong();
        if (length < 0 && request.getContentType() == null) return true;
        if (length < 0) throw new WorkServiceException(WorkErrorCode.LENGTH_REQUIRED, "NO_CONTENT_LENGTH");
        if (length > MAX_BODY_BYTES) throw new WorkServiceException(WorkErrorCode.BODY_TOO_LARGE, "BODY_TOO_LARGE");
        return true;
    }
}
