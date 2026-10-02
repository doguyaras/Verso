package com.verso.platform.core.dto;

import java.util.List;

/**
 * The single error body (reference 6.2). {@code details} only carries client-displayable {@code key=value} pairs;
 * rejected values, exception text and log-only reasons never go here (reference 7.4).
 */
public record ErrorResponse(int code, String message, String service, String path, long timestamp, String traceId,
                            List<String> details) {}
