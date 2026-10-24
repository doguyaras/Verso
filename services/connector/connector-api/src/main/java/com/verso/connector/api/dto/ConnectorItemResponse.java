package com.verso.connector.api.dto;

import com.verso.connector.api.enums.ItemState;
import java.time.Instant;
import java.util.UUID;

/**
 * A file a connector tracks (ADR-0038), for verso-admin to find what failed. The path is relative to the connector's
 * prefix and is shown here only, never logged or audited.
 *
 * @param failureCode   fixed code of the last failure or skip reason, null when synced
 * @param nextAttemptAt when a failed file is tried again (backoff); null otherwise
 */
public record ConnectorItemResponse(String path, ItemState state, String failureCode, int attempts,
                                    Instant nextAttemptAt, long sizeBytes, Instant modifiedAt, UUID documentId,
                                    Instant updatedAt) {
}
