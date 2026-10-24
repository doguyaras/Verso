package com.verso.connector.api.dto;

/**
 * Body of {@code POST /v1/admin/connectors/{id}/sync} (optional).
 *
 * @param confirmMassDeletion true lets the next run delete more documents than the deletion safety allows (ADR-0038:
 *                            a renamed share or a lost permission would otherwise look like "every file deleted"); it
 *                            applies to that one run only
 */
public record SyncRequest(Boolean confirmMassDeletion) {
}
