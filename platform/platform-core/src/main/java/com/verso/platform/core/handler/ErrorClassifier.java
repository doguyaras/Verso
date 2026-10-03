package com.verso.platform.core.handler;

import jakarta.servlet.ServletException;
import java.util.List;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.multipart.MultipartException;

/**
 * Shared decisions of the global handler and the error controller, so that a failure is classified the same way
 * whether it surfaces inside Spring MVC or only in the container's error dispatch (second-round review, 2026-10-02).
 */
final class ErrorClassifier {

    private static final int MAX_DEPTH = 32;
    /** Tomcat 11 throws this from getParameter() on undecodable input; matched by name to avoid a container type. */
    static final String TOMCAT_INVALID_PARAMETER = "org.apache.tomcat.util.http.InvalidParameterException";
    /**
     * Tomcat's own exception for an unreadable body (e.g. a broken chunked encoding). It reaches the error dispatch
     * after Spring MVC already answered 400 and used to end as code VALIDATION (third-round security review).
     */
    static final String TOMCAT_BAD_REQUEST = "org.apache.coyote.BadRequestException";

    private ErrorClassifier() {}

    /** Spring Security's own exception roots; matched by name so platform-core needs no security dependency. */
    static final List<String> SECURITY_EXCEPTIONS = List.of(
            "org.springframework.security.access.AccessDeniedException",
            "org.springframework.security.core.AuthenticationException");

    /**
     * Spring's data access exceptions for a server that is temporarily unable to answer: no connection, lock or
     * statement timeout, serialization failure. By name, like the security ones: platform-core has no spring-tx.
     */
    static final List<String> TEMPORARILY_UNAVAILABLE = List.of(
            "org.springframework.dao.DataAccessResourceFailureException",
            "org.springframework.dao.TransientDataAccessException");

    /** True when the exception (or a cause) is one of {@link #TEMPORARILY_UNAVAILABLE}, subclasses included. */
    static boolean isTemporarilyUnavailable(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            for (Class<?> type = current.getClass(); type != null; type = type.getSuperclass()) {
                if (TEMPORARILY_UNAVAILABLE.contains(type.getName())) return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }

    /** True for Spring Security's denial and authentication exceptions (subclasses included). */
    static boolean isSecurityException(Throwable t) {
        for (Class<?> type = t == null ? null : t.getClass(); type != null; type = type.getSuperclass()) {
            if (SECURITY_EXCEPTIONS.contains(type.getName())) return true;
        }
        return false;
    }

    /** Strips the ServletException wrappers the container adds around an exception thrown by a filter. */
    static Throwable unwrapServlet(Throwable t) {
        Throwable current = t;
        int depth = 0;
        while (current instanceof ServletException && current.getCause() != null && depth++ < MAX_DEPTH) {
            current = current.getCause();
        }
        return current;
    }

    /**
     * True when the request itself could not be parsed (body, multipart, parameter encoding). That is the client's
     * mistake: 400, logged at WARN, never an ERROR with a stack trace.
     */
    static boolean isMalformedRequest(Throwable t) {
        Throwable current = t;
        for (int depth = 0; current != null && depth < MAX_DEPTH; depth++) {
            if (current instanceof HttpMessageNotReadableException || current instanceof MultipartException
                    || TOMCAT_INVALID_PARAMETER.equals(current.getClass().getName())
                    || TOMCAT_BAD_REQUEST.equals(current.getClass().getName())) {
                return true;
            }
            if (current.getCause() == current) break;
            current = current.getCause();
        }
        return false;
    }
}
