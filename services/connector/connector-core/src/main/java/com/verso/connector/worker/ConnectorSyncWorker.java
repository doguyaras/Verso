package com.verso.connector.worker;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.repository.ConnectorItemRepository;
import com.verso.connector.repository.ConnectorRepository;
import com.verso.connector.repository.ConnectorRow;
import jakarta.annotation.PreDestroy;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Schedules connector syncs (ADR-0038). Every instance polls; a due connector is claimed in the database (FOR UPDATE
 * SKIP LOCKED + lease + claim token, AGENTS.md section 10), so only one instance syncs a connector at a time and a
 * stopped instance's work is resumed by another once its lease expires.
 *
 * <p>Bulkhead: one sync per instance, on its own thread. A sync may take long (a first sync of a large share); it must
 * not hold the shared scheduler thread that the ingestion and retention jobs also use. The poll only hands work over.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorSyncWorker {

    private static final Logger log = LoggerFactory.getLogger(ConnectorSyncWorker.class);

    private final ConnectorRepository connectors;
    private final ConnectorItemRepository items;
    private final SyncRunner runner;
    private final ConnectorMetrics metrics;
    private final ConnectorProperties properties;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "verso-connector-sync");
        thread.setDaemon(true);
        return thread;
    });
    private volatile boolean stopping;

    public ConnectorSyncWorker(ConnectorRepository connectors, ConnectorItemRepository items, SyncRunner runner,
                               ConnectorMetrics metrics, ConnectorProperties properties) {
        this.connectors = connectors;
        this.items = items;
        this.runner = runner;
        this.metrics = metrics;
        this.properties = properties;
    }

    /** Refreshes the metrics and, when idle, hands the due connectors to the sync thread. Never throws. */
    @Scheduled(fixedDelayString = "${verso.connector.poll-interval:PT30S}",
            initialDelayString = "${verso.connector.poll-initial-delay:PT30S}")
    public void poll() {
        try {
            metrics.refresh(connectors.all(), items.countsByConnector());
        } catch (RuntimeException e) {
            log.warn("Connector metrics not refreshed: exceptionType={}", e.getClass().getSimpleName());
        }
        if (!properties.syncEnabled() || stopping || !busy.compareAndSet(false, true)) return;
        try {
            executor.execute(() -> {
                try {
                    runDue();
                } catch (RuntimeException e) {
                    log.error("Connector poll failed: exceptionType={}", e.getClass().getSimpleName());
                } finally {
                    busy.set(false);
                }
            });
        } catch (RuntimeException e) {
            busy.set(false);
        }
    }

    /**
     * Claims and runs due connectors one after another until none is due; returns how many ran. Tests call it
     * directly with the poll switched off.
     */
    public int runDue() {
        int ran = 0;
        while (!stopping) {
            UUID token = UUID.randomUUID();
            Optional<ConnectorRow> claimed = connectors.claimDue(token, properties.lease());
            if (claimed.isEmpty()) break;
            runner.run(claimed.get(), token, () -> stopping);
            ran++;
        }
        return ran;
    }

    /** Shutdown: the running sync stops between two files, records a PARTIAL run and frees its lease. */
    @PreDestroy
    public void stop() {
        stopping = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(20, TimeUnit.SECONDS)) executor.shutdownNow();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
    }
}
