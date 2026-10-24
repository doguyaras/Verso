package com.verso.qa.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Conversation history (ADR-0023). The retention is the installation's storage-limitation policy (KVKK md. 4/2-d,
 * "ilgili mevzuatta öngörülen veya işlendikleri amaç için gerekli olan süre kadar muhafaza"; the KVKK generative AI
 * guide warns against keeping chat logs without limit). The data controller sets it per installation:
 * VERSO_HISTORY_RETENTION_DAYS (deploy/prod.env.example).
 *
 * @param retentionDays            a conversation (its title and every turn) is deleted this many days after it was
 *                                 started (default 90: a quarter, long enough for a pilot report, short enough to keep
 *                                 chat logs bounded); 1..3650
 * @param purgeEnabled             the scheduled retention job (tests drive it themselves)
 * @param purgeInterval            how often the job runs; every instance runs it, one at a time (advisory lock)
 * @param purgeInitialDelay        first run after start-up (read by the job's schedule; bound here so it is checked)
 * @param purgeBatchSize           rows per delete statement, each in its own short transaction
 * @param maxTurnsPerConversation  one conversation holds at most this many turns; the client starts a new one
 */
@ConfigurationProperties("verso.qa.history")
public record QaHistoryProperties(
        @DefaultValue("90") int retentionDays,
        @DefaultValue("true") boolean purgeEnabled,
        @DefaultValue("1h") Duration purgeInterval,
        @DefaultValue("5m") Duration purgeInitialDelay,
        @DefaultValue("500") int purgeBatchSize,
        @DefaultValue("200") int maxTurnsPerConversation) {

    public QaHistoryProperties {
        if (retentionDays < 1 || retentionDays > 3650) {
            throw new IllegalStateException("verso.qa.history.retention-days must be 1..3650");
        }
        if (!purgeInterval.isPositive()) throw new IllegalStateException("verso.qa.history.purge-interval must be positive");
        if (purgeInitialDelay.isNegative()) {
            throw new IllegalStateException("verso.qa.history.purge-initial-delay must not be negative");
        }
        if (purgeBatchSize < 1 || purgeBatchSize > 5000) {
            throw new IllegalStateException("verso.qa.history.purge-batch-size must be 1..5000");
        }
        if (maxTurnsPerConversation < 1 || maxTurnsPerConversation > 1000) {
            throw new IllegalStateException("verso.qa.history.max-turns-per-conversation must be 1..1000");
        }
    }
}
