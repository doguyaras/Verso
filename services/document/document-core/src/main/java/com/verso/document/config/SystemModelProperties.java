package com.verso.document.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The light system model (ADR-0048 phase 4, ADR-0054). Starting values (reference 1.4). The extraction rules
 * themselves and the privacy rule (no person entity) are not settings.
 *
 * @param enrichmentEnabled   retrieval adds the defining chunks of a CODE, TERM or SYSTEM the question names as extra
 *                            candidates; off by default until measured on the customer's set
 *                            (VERSO_DOCUMENT_SYSTEM_MODEL_ENRICHMENT_ENABLED)
 * @param enrichmentMaxChunks at most this many extra candidates per question (bounded hot-path work)
 * @param backfillEnabled     the scheduled job that extracts READY documents without a model of the running version
 * @param backfillInterval    pause between backfill runs
 * @param backfillBatch       documents per run, each in its own short transaction
 */
@ConfigurationProperties("verso.document.system-model")
public record SystemModelProperties(
        @DefaultValue("false") boolean enrichmentEnabled,
        @DefaultValue("2") int enrichmentMaxChunks,
        @DefaultValue("true") boolean backfillEnabled,
        @DefaultValue("1m") Duration backfillInterval,
        @DefaultValue("50") int backfillBatch) {

    public SystemModelProperties {
        if (enrichmentMaxChunks < 1 || enrichmentMaxChunks > 5) {
            throw new IllegalStateException("verso.document.system-model.enrichment-max-chunks must be 1..5");
        }
        if (!backfillInterval.isPositive()) {
            throw new IllegalStateException("verso.document.system-model.backfill-interval must be positive");
        }
        if (backfillBatch < 1 || backfillBatch > 1000) {
            throw new IllegalStateException("verso.document.system-model.backfill-batch must be 1..1000");
        }
    }
}
