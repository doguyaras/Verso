package com.verso.connector.confluence;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.HostAllowlist;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Whether a connector may reach a host on the internet (ADR-0044; llm-rules 1.1 and 1.7). Three conditions, all
 * required: the installation enabled external connector egress, the host matches its allowlist ({@code *.atlassian.net}),
 * and the application is not in local mode, which is strict (no internet, no exception). Start-up already refuses
 * external egress in local mode ({@code ConnectorEgressConfiguration}); this is the second fence at every admin change
 * and every sync. Anything but "cloud" counts as local (fail closed).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ExternalEgressPolicy {

    private final ConnectorProperties.ExternalEgress egress;
    private final HostAllowlist hosts;
    private final boolean localMode;

    public ExternalEgressPolicy(ConnectorProperties properties, Environment environment) {
        this.egress = properties.externalEgress();
        this.hosts = egress.hosts();
        this.localMode = !"cloud".equals(environment.getProperty("verso.ai.mode", "local").strip().toLowerCase(Locale.ROOT));
    }

    /** Strict local mode: the local AI mode, in which nothing may reach the internet. */
    public boolean strictLocal() {
        return localMode;
    }

    /** External egress is on and usable in this mode. */
    public boolean enabled() {
        return egress.enabled() && !strictLocal();
    }

    /** The host (a name, never an IP literal) may be reached on the internet. */
    public boolean allows(String host) {
        return enabled() && hosts.allows(host);
    }
}
