package com.verso.audit.api.dto;

import java.util.List;

/**
 * Offset page of the audit log, the same JSON shape as every other list (reference 6.3):
 * {@code {data: [...], page: {number, size, totalElements, totalPages}}}. Its own type, not document-api's
 * PageResponse: the document module publishes audit events, so audit must not depend on document (module cycle).
 */
public record AuditPageResponse(List<AuditEntryResponse> data, PageInfo page) {

    public record PageInfo(int number, int size, long totalElements, int totalPages) {
    }

    public static AuditPageResponse of(List<AuditEntryResponse> data, int number, int size, long totalElements) {
        int totalPages = size == 0 ? 0 : (int) ((totalElements + size - 1) / size);
        return new AuditPageResponse(List.copyOf(data), new PageInfo(number, size, totalElements, totalPages));
    }
}
