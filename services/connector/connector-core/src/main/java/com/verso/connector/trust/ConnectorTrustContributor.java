package com.verso.connector.trust;

import com.verso.connector.api.dto.ConnectorResponse;
import com.verso.connector.api.enums.ConfluenceDeployment;
import com.verso.connector.api.enums.ConnectorType;
import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.outbox.ConnectorOutbox;
import com.verso.connector.service.ConnectorAdminService;
import com.verso.platform.core.trust.TrustContributor;
import com.verso.platform.core.trust.TrustReport;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The connector module's part of the trust report (ADR-0050): whether connectors may reach the internet and which
 * hosts, the types of the configured connectors and whether one reads from the internet, and the age of its outbox.
 * Types and a yes/no only: no host, share, user name or credential file of a connector leaves this class.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class ConnectorTrustContributor implements TrustContributor {

    private static final Logger log = LoggerFactory.getLogger(ConnectorTrustContributor.class);
    private static final int PAGE = 200;
    private static final int MAX_PAGES = 50;

    private final ConnectorProperties properties;
    private final ConnectorAdminService connectors;
    private final ConnectorOutbox outbox;

    ConnectorTrustContributor(ConnectorProperties properties, ConnectorAdminService connectors,
                              ConnectorOutbox outbox) {
        this.properties = properties;
        this.connectors = connectors;
        this.outbox = outbox;
    }

    @Override
    public void contribute(TrustReport report) {
        ConnectorProperties.ExternalEgress egress = properties.externalEgress();
        List<String> hosts = Arrays.stream(egress.allowedHosts().split(",")).map(String::strip)
                .filter(h -> !h.isEmpty()).toList();
        report.externalConnectorEgress(egress.enabled(), hosts);
        try {
            for (int page = 0; page < MAX_PAGES; page++) {
                var batch = connectors.list(page, PAGE);
                for (ConnectorResponse c : batch.data()) {
                    boolean cloud = c.type() == ConnectorType.SHAREPOINT
                            || (c.type() == ConnectorType.CONFLUENCE && c.confluence() != null
                            && c.confluence().deployment() == ConfluenceDeployment.CLOUD);
                    report.connector(c.type().name(), cloud);
                }
                if ((long) (page + 1) * PAGE >= batch.page().totalElements()) break;
            }
        } catch (RuntimeException e) {
            report.connectorsUnreadable();
            log.warn("trust report: connector types unavailable: {}", e.getClass().getSimpleName());
        }
        try {
            outbox.store().oldestPending().ifPresent(report::outboxPendingSince);
        } catch (RuntimeException e) {
            log.warn("trust report: connector outbox unreadable: {}", e.getClass().getSimpleName());
        }
    }
}
