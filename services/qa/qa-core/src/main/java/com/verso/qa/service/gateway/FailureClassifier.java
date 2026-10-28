package com.verso.qa.service.gateway;

import com.anthropic.errors.AnthropicIoException;
import com.anthropic.errors.AnthropicServiceException;
import com.google.genai.errors.ApiException;
import com.openai.errors.OpenAIIoException;
import com.openai.errors.OpenAIServiceException;
import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkServiceException;

/**
 * What a provider failure means for the gateway (ADR-0056), across the SDKs' own exception types. Only the HTTP status
 * or the exception type is read, never the message text beyond a leading status number: provider messages can quote
 * the prompt or the key (llm-rules 2.3).
 */
public final class FailureClassifier {

    /** Spring AI's own error handler writes "<status> - <body>"; only the number is read. */
    private static final Pattern LEADING_STATUS = Pattern.compile("^(\\d{3}) - ");

    public enum Kind {
        /** 5xx, 429 or a network failure: the next model of the route is tried. */
        RETRYABLE,
        /** 401/403: the key was refused. */
        AUTH,
        /** 404: the model or deployment does not exist. */
        NOT_FOUND,
        /** Anything else (400, a local refusal, an unknown failure): the route stops. */
        OTHER
    }

    private FailureClassifier() {
    }

    public static Kind classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            Integer status = status(t);
            if (status != null) return byStatus(status);
            if (t instanceof OpenAIIoException || t instanceof AnthropicIoException || t instanceof IOException
                    || t instanceof ResourceAccessException || t instanceof WebClientRequestException
                    || (t instanceof SdkClientException && !(t.getCause() instanceof IllegalStateException))) {
                return Kind.RETRYABLE;
            }
            if (t instanceof TransientAiException) return Kind.RETRYABLE;
        }
        return Kind.OTHER;
    }

    /** The test result word stored on the connection and shown in the panel. */
    public static String status(Kind kind, Throwable failure) {
        return switch (kind) {
            case AUTH -> "AUTH_FAILED";
            case NOT_FOUND -> "MODEL_NOT_FOUND";
            case RETRYABLE -> rateLimited(failure) ? "RATE_LIMITED" : "UNREACHABLE";
            case OTHER -> "FAILED";
        };
    }

    private static boolean rateLimited(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            Integer status = status(t);
            if (status != null) return status == 429;
        }
        return false;
    }

    private static Kind byStatus(int status) {
        if (status == 429 || status >= 500) return Kind.RETRYABLE;
        if (status == 401 || status == 403) return Kind.AUTH;
        if (status == 404) return Kind.NOT_FOUND;
        return Kind.OTHER;
    }

    private static Integer status(Throwable t) {
        if (t instanceof OpenAIServiceException e) return e.statusCode();
        if (t instanceof AnthropicServiceException e) return e.statusCode();
        if (t instanceof RestClientResponseException e) return e.getStatusCode().value();
        if (t instanceof WebClientResponseException e) return e.getStatusCode().value();
        if (t instanceof SdkServiceException e) return e.statusCode();
        if (t instanceof ApiException e) return e.code();
        if (t instanceof NonTransientAiException || t instanceof TransientAiException) {
            Matcher m = LEADING_STATUS.matcher(String.valueOf(t.getMessage()));
            return m.find() ? Integer.valueOf(m.group(1)) : null;
        }
        return null;
    }
}
