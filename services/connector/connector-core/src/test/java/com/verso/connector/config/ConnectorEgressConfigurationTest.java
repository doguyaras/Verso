package com.verso.connector.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.connector.confluence.ExternalEgressPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.util.unit.DataSize;

/**
 * External connector egress (ADR-0044, llm-rules 1.1 and 1.7): off by default; local mode (strict) refuses to start with
 * it on; cloud mode allows only the listed hosts.
 */
class ConnectorEgressConfigurationTest {

    @Test
    void verify_whenEnabledInStrictLocalMode_refusesToStart() {
        ConnectorProperties.ExternalEgress on = new ConnectorProperties.ExternalEgress(true, "*.atlassian.net");

        assertThatThrownBy(() -> ConnectorEgressConfiguration.verify(on, "local"))
                .hasMessageContaining("strict local mode");
        assertThatThrownBy(() -> ConnectorEgressConfiguration.verify(on, null)).as("unset counts as local")
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ConnectorEgressConfiguration.verify(on, "cloud")).doesNotThrowAnyException();
        assertThatThrownBy(() -> ConnectorEgressConfiguration.verify(on, "LOCAL ")).isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> ConnectorEgressConfiguration.verify(
                new ConnectorProperties.ExternalEgress(false, ""), "local")).doesNotThrowAnyException();
    }

    @Test
    void externalEgress_whenEnabledWithoutHostsOrWithABroadPattern_failsFast() {
        assertThatThrownBy(() -> new ConnectorProperties.ExternalEgress(true, ""))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ConnectorProperties.ExternalEgress(true, "*.net"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void policy_whenAsked_allowsOnlyEnabledListedHostsOutsideStrictLocalMode() {
        ExternalEgressPolicy cloud = policy(new ConnectorProperties.ExternalEgress(true, "*.atlassian.net"), "cloud");
        ExternalEgressPolicy off = policy(new ConnectorProperties.ExternalEgress(false, ""), "cloud");
        ExternalEgressPolicy local = policy(new ConnectorProperties.ExternalEgress(true, "*.atlassian.net"), "local");

        assertThat(cloud.allows("acme.atlassian.net")).isTrue();
        assertThat(cloud.allows("acme.atlassian.net.evil.com")).isFalse();
        assertThat(off.allows("acme.atlassian.net")).isFalse();
        assertThat(local.strictLocal()).isTrue();
        assertThat(local.allows("acme.atlassian.net")).as("second fence behind the start-up check").isFalse();
    }

    private static ExternalEgressPolicy policy(ConnectorProperties.ExternalEgress egress, String mode) {
        ConnectorProperties properties = new ConnectorProperties(true, Duration.ofSeconds(30), Duration.ofMinutes(10),
                "10.0.0.0/8", 50, 500, DataSize.ofGigabytes(2), 100_000, 32, Duration.ofMinutes(5), Duration.ofHours(24),
                Duration.ofMinutes(1), Duration.ofMinutes(2), 20, 20, 3, 100, null, null, egress, null);
        return new ExternalEgressPolicy(properties, new MockEnvironment().withProperty("verso.ai.mode", mode));
    }
}
