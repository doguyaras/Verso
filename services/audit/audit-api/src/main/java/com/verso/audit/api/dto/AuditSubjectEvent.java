package com.verso.audit.api.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * One audit row of a data subject as the actor (ADR-0034): what, when and on which id. The actor's sub, roles and the
 * trace id are left out: the export is about the subject, who already knows their sub.
 */
public record AuditSubjectEvent(UUID id, Instant occurredAt, String action, String outcome, String targetType,
                                UUID targetId) {
}
