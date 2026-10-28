package com.verso.work.config;

import com.verso.audit.api.AuditTrail;
import com.verso.platform.core.outbox.OutboxProperties;
import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;
import com.verso.work.outbox.WorkAuditOutboxHandler;
import com.verso.work.outbox.WorkOutbox;
import com.verso.work.repository.WorkDatabase;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The work module's outbox, {@code work.outbox_event} (ADR-0039, ADR-0057), on the module's own pool: the store and the
 * relay that drains it into the audit log. Every instance runs the relay (SKIP LOCKED).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OutboxProperties.class)
public class WorkOutboxConfiguration {

    @Bean
    WorkOutbox workOutbox(WorkDatabase db, WorkAuditOutboxHandler audit, OutboxProperties properties, Clock clock,
                          MeterRegistry registry) {
        OutboxStore store = db.outboxStore();
        OutboxRelay relay = new OutboxRelay("work", store, Map.of(AuditTrail.OUTBOX_EVENT_TYPE, audit), properties,
                clock, registry);
        return new WorkOutbox(store, relay);
    }
}
