package com.verso.connector.api.dto;

import java.util.List;

/**
 * Offset page of a connector list (reference 6.3), the same JSON as the other modules' pages:
 * {@code {data: [...], page: {number, size, totalElements, totalPages}}}. Its own type so the connector api does not
 * depend on another module's DTOs.
 */
public record ConnectorPageResponse<T>(List<T> data, PageInfo page) {

    public record PageInfo(int number, int size, long totalElements, int totalPages) {
    }

    public static <T> ConnectorPageResponse<T> of(List<T> data, int number, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new ConnectorPageResponse<>(List.copyOf(data), new PageInfo(number, size, totalElements, totalPages));
    }
}
