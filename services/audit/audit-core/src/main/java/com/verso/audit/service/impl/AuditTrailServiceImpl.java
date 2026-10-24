package com.verso.audit.service.impl;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.NewEntry;
import com.verso.audit.service.AuditMetrics;
import com.verso.audit.service.AuditRecorder;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.platform.security.authority.CallerRoles;
import java.util.Locale;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The audit log's durable path (ADR-0039). {@link #capture} runs in the request, inside the publishing module's
 * transaction, and only reads the security context; {@link #store} runs later in that module's outbox relay, on the
 * audit pool, and is idempotent by the event id. Unlike {@link AuditRecorder} it throws: the publisher's transaction
 * or the relay's retry decides what a failure means.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditTrailServiceImpl implements AuditTrail {

    private static final java.util.regex.Pattern SYSTEM_COMPONENT = java.util.regex.Pattern.compile("[a-z][a-z0-9-]{2,40}");

    private final AuditEventRepository repository;
    private final AuditMetrics metrics;
    private final CallerRoles roles;
    private final String installationMode;

    public AuditTrailServiceImpl(AuditEventRepository repository, AuditMetrics metrics, CallerRoles roles,
                          @Value("${verso.ai.mode:local}") String mode) {
        this.repository = repository;
        this.metrics = metrics;
        this.roles = roles;
        this.installationMode = mode.toLowerCase(Locale.ROOT);
        if (!installationMode.equals("local") && !installationMode.equals("cloud")) {
            throw new IllegalStateException("verso.ai.mode must be local or cloud");
        }
    }

    @Override
    public AuditRecord capture(AuditEvent event) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!(authentication instanceof JwtAuthenticationToken jwt) || jwt.getToken().getSubject() == null) {
            throw new IllegalStateException("no authenticated caller");
        }
        String traceId = RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web
                ? TraceIds.current(web.getRequest()) : TraceIds.current(null);
        return new AuditRecord(UUID.randomUUID(), jwt.getToken().getSubject(), roles.of(authentication), traceId,
                event.mode() != null ? event.mode() : installationMode, event);
    }

    @Override
    public AuditRecord captureSystem(String component, AuditEvent event) {
        if (component == null || !SYSTEM_COMPONENT.matcher(component).matches()) {
            throw new IllegalArgumentException("component must be a fixed lowercase word");
        }
        // No request on a worker thread: the trace id comes from the MDC when a span set one, otherwise a new one.
        return new AuditRecord(UUID.randomUUID(), "system:" + component, java.util.List.of(), TraceIds.current(null),
                event.mode() != null ? event.mode() : installationMode, event);
    }

    @Override
    public boolean store(AuditRecord record) {
        AuditEvent event = record.event();
        boolean written = repository.insertIfAbsent(new NewEntry(record.actorSub(), record.actorRoles(), event.action(),
                event.outcome(), event.targetType(), event.targetId(), event.conversationId(), event.detailCode(),
                event.modelId(), event.citationDocumentIds(), record.traceId(), record.mode(), record.eventId()));
        if (written) metrics.recorded(event.action());
        return written;
    }
}
