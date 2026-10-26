package com.verso.audit.trust;

import com.verso.audit.repository.AuditEventRepository;
import com.verso.platform.core.trust.TrustContributor;
import com.verso.platform.core.trust.TrustReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** The audit module's part of the trust report (ADR-0050): the time of the newest event, never an event. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class AuditTrustContributor implements TrustContributor {

    private static final Logger log = LoggerFactory.getLogger(AuditTrustContributor.class);

    private final AuditEventRepository events;

    AuditTrustContributor(AuditEventRepository events) {
        this.events = events;
    }

    @Override
    public void contribute(TrustReport report) {
        try {
            events.latestOccurredAt().ifPresent(report::lastAuditEventAt);
        } catch (RuntimeException e) {
            log.warn("trust report: audit log unreadable: {}", e.getClass().getSimpleName());
        }
    }
}
