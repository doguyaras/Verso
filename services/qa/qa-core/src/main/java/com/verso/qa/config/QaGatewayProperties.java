package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The model gateway (ADR-0056). Starting values (BA), not rules.
 *
 * @param checkTimeout         bound of the validation call on save, rotation or "test" (a tiny completion)
 * @param cacheTtl             how long an instance keeps the routes, connections, prices and budgets it read; a
 *                             change made on another instance is seen within this time (the instance that made it
 *                             sees it at once)
 * @param minTeamUsers         k of the team rows of the usage report: a team is shown only with at least this many
 *                             distinct users that month; never below 5 (works-council rule)
 * @param usageRetentionMonths how long usage rows (and the team digests) are kept
 */
@ConfigurationProperties("verso.qa.gateway")
public record QaGatewayProperties(
        @DefaultValue("15s") Duration checkTimeout,
        @DefaultValue("10s") Duration cacheTtl,
        @DefaultValue("5") int minTeamUsers,
        @DefaultValue("13") int usageRetentionMonths) {

    /** The k-anonymity floor of team rows: configuration may raise it, never lower it. */
    public static final int MIN_TEAM_USERS_FLOOR = 5;

    public QaGatewayProperties {
        if (checkTimeout.isNegative() || checkTimeout.isZero() || checkTimeout.toSeconds() > 120) {
            throw new IllegalStateException("verso.qa.gateway.check-timeout must be 1s..120s");
        }
        if (cacheTtl.isNegative() || cacheTtl.toSeconds() > 300) {
            throw new IllegalStateException("verso.qa.gateway.cache-ttl must be 0..300s");
        }
        if (minTeamUsers < MIN_TEAM_USERS_FLOOR) {
            throw new IllegalStateException("verso.qa.gateway.min-team-users must be at least " + MIN_TEAM_USERS_FLOOR);
        }
        if (usageRetentionMonths < 1 || usageRetentionMonths > 120) {
            throw new IllegalStateException("verso.qa.gateway.usage-retention-months must be 1..120");
        }
    }
}
