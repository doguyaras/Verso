package com.verso.platform.core.dto;

/**
 * Response envelope (reference 6.2). Decision for Verso (ADR-0004): errors are always enveloped, successful
 * responses are returned raw. The two styles are never mixed for successes.
 */
public record ApiResponse<T>(boolean ok, T data, ErrorResponse error) {

    public static ApiResponse<Void> error(ErrorResponse error) {
        return new ApiResponse<>(false, null, error);
    }
}
