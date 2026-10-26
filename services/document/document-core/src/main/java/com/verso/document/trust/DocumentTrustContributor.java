package com.verso.document.trust;

import com.verso.document.outbox.DocumentOutbox;
import com.verso.platform.core.trust.TrustContributor;
import com.verso.platform.core.trust.TrustReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** The document module's part of the trust report (ADR-0050): the age of its audit outbox, nothing else. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class DocumentTrustContributor implements TrustContributor {

    private static final Logger log = LoggerFactory.getLogger(DocumentTrustContributor.class);

    private final DocumentOutbox outbox;

    DocumentTrustContributor(DocumentOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void contribute(TrustReport report) {
        try {
            outbox.store().oldestPending().ifPresent(report::outboxPendingSince);
        } catch (RuntimeException e) {
            log.warn("trust report: document outbox unreadable: {}", e.getClass().getSimpleName());
        }
    }
}
