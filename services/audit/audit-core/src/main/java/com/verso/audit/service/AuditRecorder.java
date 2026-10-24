package com.verso.audit.service;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.config.AuditProperties;
import com.verso.audit.repository.AuditEventRepository;
import com.verso.audit.repository.AuditEventRepository.NewEntry;
import com.verso.platform.observability.tracing.TraceIds;
import com.verso.platform.security.authority.CallerRoles;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.event.EventListener;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Writes the {@link AuditEvent}s other modules publish (ADR-0026), and adds who, when and where: the token subject and
 * Verso roles of the current request, the request's trace id and the installation's AI mode. The time is the
 * database's, not ours.
 *
 * <p><b>The direct path (ADR-0039).</b> The document and qa modules no longer publish here: they write an outbox row
 * in their business transaction and their relay stores it through {@link com.verso.audit.service.impl.AuditTrailServiceImpl}. What still arrives here
 * has no business transaction of its own module to join: access denials and the privacy module's subject requests.
 *
 * <p><b>Synchronous and after the commit.</b> Publishers raise the event after their own transaction committed, and
 * this listener runs in the request thread, so the row exists when the response is sent and the request's identity and
 * trace id are at hand. An asynchronous queue would lose rows at a crash and hide a backlog. The price is bounded:
 * one insert, a 1 s connection wait at most, and after a failure the audit database is skipped for
 * {@code verso.audit.breaker-open} so an outage costs one wait, not one per request.
 *
 * <p><b>Never breaks the request.</b> Any failure is caught here: the operation already happened and its caller must
 * get the answer. The loss is made visible instead: {@code verso.audit.events.not.stored} and the alert
 * AuditEventNotStored (page), and one log line with the exception type only, never its message (a constraint message
 * could quote the row).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditEventRepository repository;
    private final AuditMetrics metrics;
    private final CallerRoles roles;
    private final AuditProperties properties;
    private final String installationMode;
    /** Nanos until which the audit database is not tried again; JVM-local on purpose (a protective pause per instance). */
    private final AtomicLong skipUntil = new AtomicLong();

    public AuditRecorder(AuditEventRepository repository, AuditMetrics metrics, CallerRoles roles,
                         AuditProperties properties, @Value("${verso.ai.mode:local}") String mode) {
        this.repository = repository;
        this.metrics = metrics;
        this.roles = roles;
        this.properties = properties;
        this.installationMode = mode.toLowerCase(Locale.ROOT);
        if (!installationMode.equals("local") && !installationMode.equals("cloud")) {
            throw new IllegalStateException("verso.ai.mode must be local or cloud");
        }
    }

    @EventListener
    public void on(AuditEvent event) {
        record(event, SecurityContextHolder.getContext().getAuthentication());
    }

    /** Records the event for this authentication; never throws. */
    public void record(AuditEvent event, Authentication authentication) {
        try {
            if (!(authentication instanceof JwtAuthenticationToken jwt) || jwt.getToken().getSubject() == null) {
                metrics.notStored("invalid");
                log.error("Audit event not stored: code=AUDIT_NO_ACTOR reason=NO_AUTHENTICATED_CALLER action={}",
                        event.action());
                return;
            }
            if (System.nanoTime() - skipUntil.get() < 0) {
                metrics.notStored("circuit_open");
                return;
            }
            try {
                repository.insert(new NewEntry(jwt.getToken().getSubject(), roles.of(authentication), event.action(),
                        event.outcome(), event.targetType(), event.targetId(), event.conversationId(),
                        event.detailCode(), event.modelId(), event.citationDocumentIds(), traceId(),
                        event.mode() != null ? event.mode() : installationMode, UUID.randomUUID()));
                metrics.recorded(event.action());
            } catch (RuntimeException e) {
                skipUntil.set(System.nanoTime() + properties.breakerOpen().toNanos());
                metrics.notStored("failed");
                // Type only: a driver or constraint message could quote the row or the connection settings.
                log.error("Audit event not stored: code=AUDIT_WRITE_FAILED reason=DATABASE action={} exceptionType={}",
                        event.action(), e.getClass().getSimpleName());
            }
        } catch (RuntimeException e) {
            metrics.notStored("failed");
            log.error("Audit event not stored: code=AUDIT_WRITE_FAILED reason=UNEXPECTED exceptionType={}",
                    e.getClass().getSimpleName());
        }
    }

    private static String traceId() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes web
                ? TraceIds.current(web.getRequest()) : TraceIds.current(null);
    }
}
