package com.verso.connector.config;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.actuate.info.InfoContributor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * External connector egress (ADR-0044; llm-rules 1.1): the start-up refusal in strict local mode and the line on
 * /actuator/info that says whether connectors may reach the internet and which hosts, so the egress statement of an
 * installation can be checked from outside.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorEgressConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ConnectorEgressConfiguration.class);

    /**
     * Local mode promises no internet egress (llm-rules 1.1, no exception): with verso.ai.mode=local the application does
     * not start while external connector egress is on. Anything but "cloud" counts as local (fail closed).
     */
    @Bean
    InitializingBean connectorEgressCheck(ConnectorProperties properties, Environment environment) {
        return () -> verify(properties.externalEgress(), environment.getProperty("verso.ai.mode", "local"));
    }

    static void verify(ConnectorProperties.ExternalEgress egress, String aiMode) {
        if (!egress.enabled()) return;
        boolean local = !"cloud".equals(aiMode == null ? "local" : aiMode.strip().toLowerCase(Locale.ROOT));
        if (local) {
            throw new IllegalStateException("verso.connector.external-egress.enabled=true is refused in strict local mode "
                    + "(verso.ai.mode=local): local mode promises no internet egress. Confluence Cloud and SharePoint Online need cloud mode "
                    + "(ADR-0044, ADR-0045)");
        }
        log.warn("Connector external egress enabled: aiMode=cloud hosts={}", egress.allowedHosts().split(",").length);
    }

    /** /actuator/info: "connectors": {"externalEgress": false} or the allowed host patterns (configuration, no secret). */
    @Bean
    InfoContributor connectorEgressInfo(ConnectorProperties properties) {
        ConnectorProperties.ExternalEgress egress = properties.externalEgress();
        return builder -> builder.withDetail("connectors", Map.of(
                "externalEgress", egress.enabled(),
                "externalHosts", egress.enabled()
                        ? Arrays.stream(egress.allowedHosts().split(",")).map(String::strip).filter(h -> !h.isEmpty())
                                .toList()
                        : java.util.List.of()));
    }
}
