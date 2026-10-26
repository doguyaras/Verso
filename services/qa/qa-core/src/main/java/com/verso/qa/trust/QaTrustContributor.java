package com.verso.qa.trust;

import com.verso.platform.core.trust.TrustContributor;
import com.verso.platform.core.trust.TrustReport;
import com.verso.qa.outbox.QaOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/** The qa module's part of the trust report (ADR-0050): the age of its audit outbox, nothing else. */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class QaTrustContributor implements TrustContributor {

    private static final Logger log = LoggerFactory.getLogger(QaTrustContributor.class);

    private final QaOutbox outbox;

    QaTrustContributor(QaOutbox outbox) {
        this.outbox = outbox;
    }

    @Override
    public void contribute(TrustReport report) {
        try {
            outbox.store().oldestPending().ifPresent(report::outboxPendingSince);
        } catch (RuntimeException e) {
            log.warn("trust report: qa outbox unreadable: {}", e.getClass().getSimpleName());
        }
    }
}
