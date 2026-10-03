package com.verso.document.api.dto;

import java.util.List;

/** Offset page of a list endpoint (reference 6.3): {@code {data: [...], page: {number, size, totalElements, totalPages}}}. */
public record PageResponse<T>(List<T> data, PageInfo page) {

    public record PageInfo(int number, int size, long totalElements, int totalPages) {
    }

    public static <T> PageResponse<T> of(List<T> data, int number, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new PageResponse<>(List.copyOf(data), new PageInfo(number, size, totalElements, totalPages));
    }
}
