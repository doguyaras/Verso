package com.verso.document.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The backfill that re-indexes READY documents with context headers (ADR-0061). Starting values (reference 1.4); the
 * header rules themselves are code (ChunkContext.VERSION), not settings.
 *
 * @param embedHeader      the header goes into the embedding input as well as the full-text index; false keeps it in
 *                         the full-text index only (ADR-0022 measured a name prefix lowering the cosine of answerable
 *                         questions; this lets an installation measure both). A change re-indexes every document.
 * @param backfillEnabled  the scheduled job that re-embeds documents indexed with an older header version or none
 * @param backfillInterval pause between runs
 * @param backfillBatch    documents per run; each is embedded outside any transaction and written in one short one
 * @param backfillLease    how long a document is held by one instance before another may take it
 * @param backfillPause    pause between two embedding batches of the backfill: the embedding server serves questions
 *                         from the same slots, and a question's embedding (10 s bound) must not queue behind an
 *                         hour of re-indexing after an upgrade (review finding, 2026-10-30); zero turns it off
 */
@ConfigurationProperties("verso.document.context-header")
public record ContextHeaderProperties(
        @DefaultValue("true") boolean embedHeader,
        @DefaultValue("true") boolean backfillEnabled,
        @DefaultValue("30s") Duration backfillInterval,
        @DefaultValue("5") int backfillBatch,
        @DefaultValue("10m") Duration backfillLease,
        @DefaultValue("250ms") Duration backfillPause) {

    public ContextHeaderProperties {
        if (!backfillInterval.isPositive()) {
            throw new IllegalStateException("verso.document.context-header.backfill-interval must be positive");
        }
        if (backfillBatch < 1 || backfillBatch > 100) {
            throw new IllegalStateException("verso.document.context-header.backfill-batch must be 1..100");
        }
        if (backfillPause.isNegative() || backfillPause.compareTo(Duration.ofSeconds(10)) > 0) {
            throw new IllegalStateException("verso.document.context-header.backfill-pause must be 0..10s");
        }
        if (backfillLease.compareTo(Duration.ofMinutes(1)) < 0) {
            throw new IllegalStateException("verso.document.context-header.backfill-lease must be at least 1m");
        }
    }
}
