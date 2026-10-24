package com.verso.connector.domain;

import com.verso.connector.api.enums.ConnectorHealth;
import java.time.Duration;
import java.time.Instant;

/**
 * Derives a connector's health from facts, not from the last run's badge (ADR-0038). A connector is STALE when its last
 * successful sync (or, never synced, its creation) is older than {@code staleFactor} schedule intervals: the same rule
 * as the ConnectorSyncStale alert, so the API and the pager agree.
 */
public final class HealthRule {

    private HealthRule() {}

    public static ConnectorHealth health(boolean enabled, boolean deleting, Instant createdAt, Instant lastSuccessAt,
                                         int consecutiveFailures, int scheduleMinutes, int staleFactor, Instant now) {
        if (deleting) return ConnectorHealth.DELETING;
        if (!enabled) return ConnectorHealth.DISABLED;
        Instant since = lastSuccessAt != null ? lastSuccessAt : createdAt;
        if (Duration.between(since, now).compareTo(staleAfter(scheduleMinutes, staleFactor)) > 0) {
            return ConnectorHealth.STALE;
        }
        if (consecutiveFailures > 0) return ConnectorHealth.FAILING;
        return lastSuccessAt == null ? ConnectorHealth.NEVER_SYNCED : ConnectorHealth.OK;
    }

    public static Duration staleAfter(int scheduleMinutes, int staleFactor) {
        return Duration.ofMinutes((long) scheduleMinutes * staleFactor);
    }
}
