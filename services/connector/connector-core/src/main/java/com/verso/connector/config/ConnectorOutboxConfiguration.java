package com.verso.connector.config;

import com.verso.audit.api.AuditTrail;
import com.verso.connector.outbox.ConnectorAuditOutboxHandler;
import com.verso.connector.outbox.ConnectorOutbox;
import com.verso.connector.repository.ConnectorDatabase;
import com.verso.platform.core.outbox.OutboxProperties;
import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The connector module's outbox, {@code connector.outbox_event} (ADR-0039, reference 11.2), on the module's own pool:
 * the store and the relay that drains it into the audit log. Every instance runs the relay (SKIP LOCKED).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OutboxProperties.class)
public class ConnectorOutboxConfiguration {

    @Bean
    ConnectorOutbox connectorOutbox(ConnectorDatabase db, ConnectorAuditOutboxHandler audit, OutboxProperties properties,
                                    Clock clock, MeterRegistry registry) {
        OutboxStore store = db.outboxStore();
        OutboxRelay relay = new OutboxRelay("connector", store, Map.of(AuditTrail.OUTBOX_EVENT_TYPE, audit), properties,
                clock, registry);
        return new ConnectorOutbox(store, relay);
    }
}
