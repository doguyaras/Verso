package com.verso.audit.api.dto;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * An {@link AuditEvent} with who and where filled in at the moment of the operation (ADR-0039), as it travels through a
 * publishing module's outbox to the audit log. Still ids only: the opaque token subject, Verso's two role names, the
 * request's trace id and the AI mode, checked here with the same rules as the CHECK constraints of {@code audit.event},
 * so a row the audit log would refuse never enters an outbox.
 *
 * @param eventId    the delivery key: the outbox row id and the unique {@code audit.event.event_id}; a second delivery
 *                   of the same record is a no-op
 * @param actorSub   the validated token's subject
 * @param actorRoles Verso roles of the caller ({@code verso-admin}, {@code verso-user})
 * @param traceId    the request's trace id (32 hex digits)
 * @param mode       "local" or "cloud": the event's own, or the installation's at the time of the operation
 * @param event      what happened
 */
public record AuditRecord(UUID eventId, String actorSub, List<String> actorRoles, String traceId, String mode,
                          AuditEvent event) {

    private static final Set<String> ROLES = Set.of("verso-admin", "verso-user");
    private static final Pattern TRACE = Pattern.compile("[0-9a-f]{32}");

    public AuditRecord {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(event, "event");
        if (actorSub == null || actorSub.isEmpty() || actorSub.length() > 255) {
            throw new IllegalArgumentException("actorSub must be 1..255 characters");
        }
        actorRoles = actorRoles == null ? List.of() : List.copyOf(actorRoles);
        if (!ROLES.containsAll(actorRoles)) throw new IllegalArgumentException("actorRoles must be Verso roles");
        if (traceId == null || !TRACE.matcher(traceId).matches()) {
            throw new IllegalArgumentException("traceId must be 32 hex digits");
        }
        if (!"local".equals(mode) && !"cloud".equals(mode)) {
            throw new IllegalArgumentException("mode must be local or cloud");
        }
    }
}
