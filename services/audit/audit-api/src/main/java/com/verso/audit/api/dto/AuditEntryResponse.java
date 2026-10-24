package com.verso.audit.api.dto;

import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditOutcome;
import com.verso.audit.api.enums.AuditTargetType;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One row of the audit log as /v1/admin/audit returns it (ADR-0026). {@code actor} is the opaque token subject the
 * identity provider issued; there is no name, e-mail or address. "What" is ids and fixed words only.
 */
public record AuditEntryResponse(UUID id, Instant occurredAt, String actor, List<String> actorRoles, AuditAction action,
                                 AuditOutcome outcome, AuditTargetType targetType, UUID targetId, UUID conversationId,
                                 String detailCode, String modelId, List<UUID> citationDocumentIds, String traceId,
                                 String mode) {
}
