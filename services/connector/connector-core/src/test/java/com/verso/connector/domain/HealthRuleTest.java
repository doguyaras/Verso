package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.connector.api.enums.ConnectorHealth;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Health from facts (ADR-0038): an old success is not "OK". */
class HealthRuleTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final Instant CREATED = NOW.minus(Duration.ofDays(10));

    @Test
    void health_whenTheLastSuccessIsOlderThanThreeIntervals_isStale_evenWithoutAFailedRun() {
        assertThat(health(true, NOW.minus(Duration.ofMinutes(181)), 0, 60)).isEqualTo(ConnectorHealth.STALE);
        assertThat(health(true, NOW.minus(Duration.ofMinutes(179)), 0, 60)).isEqualTo(ConnectorHealth.OK);
    }

    @Test
    void health_whenTheLastRunFailedButTheDataIsFresh_isFailing() {
        assertThat(health(true, NOW.minus(Duration.ofMinutes(30)), 2, 60)).isEqualTo(ConnectorHealth.FAILING);
    }

    @Test
    void health_whenNeverSynced_isNeverSyncedUntilItBecomesStale() {
        assertThat(HealthRule.health(true, false, NOW.minusSeconds(60), null, 0, 60, 3, NOW))
                .isEqualTo(ConnectorHealth.NEVER_SYNCED);
        assertThat(HealthRule.health(true, false, CREATED, null, 0, 60, 3, NOW)).isEqualTo(ConnectorHealth.STALE);
    }

    @Test
    void health_whenPausedOrDeleted_saysSo() {
        assertThat(health(false, null, 0, 60)).isEqualTo(ConnectorHealth.DISABLED);
        assertThat(HealthRule.health(false, true, CREATED, null, 0, 60, 3, NOW)).isEqualTo(ConnectorHealth.DELETING);
    }

    private static ConnectorHealth health(boolean enabled, Instant lastSuccess, int failures, int schedule) {
        return HealthRule.health(enabled, false, CREATED, lastSuccess, failures, schedule, 3, NOW);
    }
}
