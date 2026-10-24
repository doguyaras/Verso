package com.verso.qa.config;

import com.verso.audit.api.AuditTrail;
import com.verso.platform.core.outbox.OutboxProperties;
import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;
import com.verso.qa.outbox.QaAuditOutboxHandler;
import com.verso.qa.outbox.QaOutbox;
import com.verso.qa.repository.QaDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The qa module's outbox, {@code qa.outbox_event} (ADR-0039, reference 11.2), on the module's own pool (QaDatabase):
 * the store and the relay that drains it into the audit log. Every instance runs the relay (SKIP LOCKED).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OutboxProperties.class)
public class QaOutboxConfiguration {

    /** MANDATORY: a row is written only inside {@link QaDatabase#inTransaction}, never in autocommit. */
    @Bean
    QaOutbox qaOutbox(QaDatabase db, QaAuditOutboxHandler audit, OutboxProperties properties, Clock clock,
                      MeterRegistry registry) {
        OutboxStore store = new OutboxStore("qa.outbox_event", db.jdbc(), db::inTransactionNow);
        OutboxRelay relay = new OutboxRelay("qa", store, Map.of(AuditTrail.OUTBOX_EVENT_TYPE, audit), properties, clock,
                registry);
        return new QaOutbox(store, relay);
    }
}
