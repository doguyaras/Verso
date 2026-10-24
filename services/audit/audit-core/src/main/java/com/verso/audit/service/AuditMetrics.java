package com.verso.audit.service;

import com.verso.audit.api.enums.AuditAction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Metrics of the audit log (ADR-0026, ADR-0014). Labels are the closed action enum and fixed reasons; never an
 * account, an id or a text.
 *
 * <ul>
 *   <li>{@code verso.audit.events{action}}: rows written.</li>
 *   <li>{@code verso.audit.events.not.stored{reason}}: events that were not written ("failed": the write threw;
 *       "circuit_open": the audit database is skipped for a few seconds after a failure; "invalid": no usable actor).
 *       The alert AuditEventNotStored watches this one (docs/runbooks/audit-write-failed.md).</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class AuditMetrics {

    private final MeterRegistry registry;
    private final Map<AuditAction, Counter> recorded = new EnumMap<>(AuditAction.class);
    private final Map<String, Counter> notStored = new ConcurrentHashMap<>();

    public AuditMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Registered at 0 up front: a series that first appears at 1 has no increase() for the alert.
        for (String reason : new String[]{"failed", "circuit_open", "invalid"}) notStoredCounter(reason);
        for (AuditAction action : AuditAction.values()) {
            recorded.put(action, Counter.builder("verso.audit.events").tag("action", action.name().toLowerCase(Locale.ROOT))
                    .description("Audit rows written").register(registry));
        }
    }

    public void recorded(AuditAction action) {
        recorded.get(action).increment();
    }

    public void notStored(String reason) {
        notStoredCounter(reason).increment();
    }

    private Counter notStoredCounter(String reason) {
        return notStored.computeIfAbsent(reason, r -> Counter.builder("verso.audit.events.not.stored")
                .tag("reason", r).description("Audit events that could not be stored").register(registry));
    }
}
