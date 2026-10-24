package com.verso.connector.worker;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.repository.ConnectorItemRepository.Counts;
import com.verso.connector.repository.ConnectorRow;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.MultiGauge;
import io.micrometer.core.instrument.Tags;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Connector metrics (ADR-0038). The stale alert reads {@code verso_connector_sync_lag_seconds} against
 * {@code verso_connector_schedule_seconds}: the lag is computed at scrape time from the last successful sync, so it keeps
 * growing while syncs fail or stop altogether, whatever the last run's badge said. Series are labelled with the
 * connector id only, never its name, host or share. Only enabled connectors that are not being deleted get the
 * lag and schedule series; every instance exports the same values (alerts use max by connector).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorMetrics {

    /** Codes are fixed words; anything else is counted as OTHER so the label set stays bounded. */
    private static final Set<String> REASONS = Set.of("READ_FAILED", "ACCESS_DENIED", "FILE_VANISHED", "TOO_LARGE",
            "EMPTY", "UNSUPPORTED", "UNSAFE_LINK", "COLLECTION_FULL", "QUEUE_FULL", "READ_TIMEOUT");

    private final MeterRegistry registry;
    private final Clock clock;
    private final MultiGauge lag;
    private final MultiGauge schedule;
    private final MultiGauge lastSuccess;
    private final MultiGauge items;

    public ConnectorMetrics(MeterRegistry registry, Clock clock) {
        this.registry = registry;
        this.clock = clock;
        this.lag = MultiGauge.builder("verso.connector.sync.lag.seconds")
                .description("Seconds since the connector's last successful sync (its creation if never)")
                .register(registry);
        this.schedule = MultiGauge.builder("verso.connector.schedule.seconds")
                .description("The connector's sync interval").register(registry);
        this.lastSuccess = MultiGauge.builder("verso.connector.last.success.timestamp.seconds")
                .description("End of the connector's last successful sync, epoch seconds; 0 if never")
                .register(registry);
        this.items = MultiGauge.builder("verso.connector.items")
                .description("Files a connector tracks, by state").register(registry);
    }

    public void run(String status) {
        Counter.builder("verso.connector.sync.runs").tag("status", status).register(registry).increment();
    }

    public void document(String change) {
        Counter.builder("verso.connector.documents").tag("change", change).register(registry).increment();
    }

    public void itemFailed(String code) {
        Counter.builder("verso.connector.items.failed").tag("reason", REASONS.contains(code) ? code : "OTHER")
                .register(registry).increment();
    }

    /** Replaces the per-connector series with the current rows (called by every poll). */
    public void refresh(List<ConnectorRow> connectors, Map<UUID, Counts> counts) {
        List<MultiGauge.Row<?>> lagRows = new ArrayList<>();
        List<MultiGauge.Row<?>> scheduleRows = new ArrayList<>();
        List<MultiGauge.Row<?>> successRows = new ArrayList<>();
        List<MultiGauge.Row<?>> itemRows = new ArrayList<>();
        for (ConnectorRow c : connectors) {
            Tags tags = Tags.of("connector", c.id().toString());
            Counts n = counts.getOrDefault(c.id(), new Counts(0, 0, 0));
            itemRows.add(MultiGauge.Row.of(tags.and("state", "synced"), n.synced()));
            itemRows.add(MultiGauge.Row.of(tags.and("state", "failed"), n.failed()));
            itemRows.add(MultiGauge.Row.of(tags.and("state", "skipped"), n.skipped()));
            successRows.add(MultiGauge.Row.of(tags, c.lastSuccessAt() == null ? 0 : c.lastSuccessAt().getEpochSecond()));
            if (!c.enabled() || c.deleting()) continue;
            Instant since = c.lastSuccessAt() != null ? c.lastSuccessAt() : c.createdAt();
            lagRows.add(MultiGauge.Row.of(tags, since,
                    s -> Math.max(0, clock.instant().getEpochSecond() - s.getEpochSecond())));
            scheduleRows.add(MultiGauge.Row.of(tags, c.scheduleMinutes() * 60L));
        }
        lag.register(lagRows, true);
        schedule.register(scheduleRows, true);
        lastSuccess.register(successRows, true);
        items.register(itemRows, true);
    }
}
