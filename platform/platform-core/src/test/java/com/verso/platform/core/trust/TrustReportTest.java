package com.verso.platform.core.trust;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** What the modules add up to in the trust report (ADR-0050). */
class TrustReportTest {

    @Test
    void outboxPendingSince_keepsTheOldestOfAllModulesAndIgnoresNull() {
        TrustReport report = new TrustReport();
        report.outboxPendingSince(Instant.parse("2026-10-26T10:00:00Z"));
        report.outboxPendingSince(Instant.parse("2026-10-26T09:00:00Z"));
        report.outboxPendingSince(null);
        report.outboxPendingSince(Instant.parse("2026-10-26T11:00:00Z"));
        assertThat(report.oldestOutboxAt()).isEqualTo(Instant.parse("2026-10-26T09:00:00Z"));
    }

    @Test
    void externalConnectorEgress_whenDisabled_listsNoHost() {
        TrustReport report = new TrustReport();
        report.externalConnectorEgress(false, List.of("*.atlassian.net"));
        assertThat(report.externalConnectorEgress()).isFalse();
        assertThat(report.egressHosts()).isEmpty();
        report.externalConnectorEgress(true, List.of("*.atlassian.net"));
        assertThat(report.egressHosts()).containsExactly("*.atlassian.net");
    }

    @Test
    void connector_collectsDistinctSortedTypesAndRemembersACloudOne() {
        TrustReport report = new TrustReport();
        report.connector("SMB", false);
        report.connector("CONFLUENCE", true);
        report.connector("SMB", false);
        assertThat(report.connectorTypes()).containsExactly("CONFLUENCE", "SMB");
        assertThat(report.cloudConnectorPresent()).isTrue();
        assertThat(report.connectorsKnown()).isTrue();
        report.connectorsUnreadable();
        assertThat(report.connectorsKnown()).as("unreadable is not 'none'").isFalse();
    }

    @Test
    void emptyReport_saysNothingIsKnownToBeOpen() {
        TrustReport report = new TrustReport();
        assertThat(report.externalConnectorEgress()).isFalse();
        assertThat(report.cloudConnectorPresent()).isFalse();
        assertThat(report.lastAuditEventAt()).isNull();
        assertThat(report.oldestOutboxAt()).isNull();
    }
}
