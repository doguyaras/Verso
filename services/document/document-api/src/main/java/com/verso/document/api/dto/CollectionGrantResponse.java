package com.verso.document.api.dto;

import com.verso.document.api.enums.PrincipalType;
import java.time.Instant;
import java.util.UUID;

/**
 * One grant of a collection, shown to verso-admin only (ADR-0028). {@code principal} is the group claim value or the
 * sub exactly as granted; it is directory data, never logged (security-rules 4) and never shown to readers.
 */
public record CollectionGrantResponse(UUID id, PrincipalType principalType, String principal, Instant createdAt) {
}
