package com.verso.qa.controller;

import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Bounds the JSON body of POST /v1/questions before it is read (phase 5 review S2): a 1000-character question fits in a
 * few KB, while Jackson would otherwise parse up to 20M characters into the heap before validation says 400. A body
 * without Content-Length (chunked) cannot be bounded here and is refused with 411.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QuestionBodyLimit implements HandlerInterceptor, WebMvcConfigurer {

    /** 1000 characters of up to 4 UTF-8 bytes, escaped JSON included, with room to spare. */
    static final long MAX_BODY_BYTES = 16 * 1024;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(this).addPathPatterns("/v1/questions");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!"POST".equals(request.getMethod())) return true;
        long length = request.getContentLengthLong();
        if (length < 0) throw new QaServiceException(QaErrorCode.QUESTION_LENGTH_REQUIRED, "NO_CONTENT_LENGTH");
        if (length > MAX_BODY_BYTES) throw new QaServiceException(QaErrorCode.QUESTION_BODY_TOO_LARGE, "BODY_TOO_LARGE");
        return true;
    }
}
