package com.verso.connector.worker;

import com.verso.platform.core.outbox.OutboxProperties;
import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.connector.outbox.ConnectorOutbox;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drives the connector outbox relay (ADR-0039): one poll every {@code verso.outbox.poll-interval} on every instance (SKIP
 * LOCKED shares the rows), and the failure retention every {@code verso.outbox.purge-interval}. One failure must not
 * stop the next run (reference 23.4 scheduler wrapper); the log carries the exception type only.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorOutboxRelayJob {

    private static final Logger log = LoggerFactory.getLogger(ConnectorOutboxRelayJob.class);

    private final OutboxRelay relay;
    private final OutboxProperties properties;

    public ConnectorOutboxRelayJob(ConnectorOutbox outbox, OutboxProperties properties) {
        this.relay = outbox.relay();
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${verso.outbox.poll-interval:1s}", initialDelayString = "${verso.outbox.poll-interval:1s}")
    public void poll() {
        if (!properties.enabled()) return;
        try {
            relay.runOnce();
        } catch (RuntimeException e) {
            relay.refreshGauges();
            log.error("Outbox poll failed: outbox=connector exceptionType={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${verso.outbox.purge-interval:1h}", initialDelayString = "${verso.outbox.purge-interval:1h}")
    public void purge() {
        if (!properties.enabled()) return;
        try {
            relay.purgeDead();
        } catch (RuntimeException e) {
            log.error("Outbox purge failed: outbox=connector exceptionType={}", e.getClass().getSimpleName());
        }
    }
}
