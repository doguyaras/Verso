package com.verso.document.config;

import com.verso.audit.api.AuditTrail;
import com.verso.document.config.DocumentPersistenceConfiguration.DocumentDataSource;
import com.verso.document.outbox.DocumentAuditOutboxHandler;
import com.verso.document.outbox.DocumentOutbox;
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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The document module's outbox, {@code document.outbox_event} (ADR-0039, reference 11.2): the store on the module's
 * own pool and the relay that drains it into the audit log. Every process with a web server runs the relay (API-only
 * and worker-only alike: both write rows), so it does not follow {@code verso.ingestion.enabled}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(OutboxProperties.class)
public class DocumentOutboxConfiguration {

    /**
     * MANDATORY: a row is written only while a transaction of the document pool is open on this thread (Boot's
     * transaction manager binds exactly this DataSource), never in autocommit next to the change.
     */
    @Bean
    DocumentOutbox documentOutbox(JdbcClient jdbc, DocumentDataSource dataSource, DocumentAuditOutboxHandler audit,
                                  OutboxProperties properties, Clock clock, MeterRegistry registry) {
        OutboxStore store = new OutboxStore("document.outbox_event", jdbc,
                () -> TransactionSynchronizationManager.hasResource(dataSource));
        OutboxRelay relay = new OutboxRelay("document", store, Map.of(AuditTrail.OUTBOX_EVENT_TYPE, audit), properties,
                clock, registry);
        return new DocumentOutbox(store, relay);
    }
}
