package com.verso.connector.config;

import com.verso.connector.domain.NetworkAllowlist;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * The connector module's settings (ADR-0038); starting values, measured later (AGENTS.md section 2).
 *
 * @param syncEnabled           the worker claims due connectors (tests switch it off and drive runs themselves)
 * @param pollInterval          how often an idle instance looks for a due connector
 * @param lease                 how long a claimed connector stays locked without a renewal; renewed while it runs
 * @param allowedNetworks       comma-separated CIDR blocks a connector may connect to, private ranges only; empty: none
 *                              (VERSO_CONNECTOR_ALLOWED_NETWORKS)
 * @param maxConnectors         connectors of one installation
 * @param maxFilesPerRun        downloads per run; the rest waits for the next run (rate limit)
 * @param maxBytesPerRun        bytes downloaded per run (rate limit)
 * @param maxListedEntries      directory entries one listing may walk; a larger source is listed incompletely and
 *                              then nothing is deleted
 * @param maxDepth              folder depth below the prefix
 * @param itemRetryBase         backoff of a failed file: base · 2^(attempts-1) ...
 * @param itemRetryMax          ... up to this
 * @param failureRetryBase      backoff of a failed run: base · 2^(failures-1), at most the schedule interval
 * @param continuationDelay     next run after a run that left work for later (limits, backlog)
 * @param deletionSafetyPercent a run deletes at most this share of the tracked files without confirmation ...
 * @param deletionSafetyMinimum ... once it deletes more than this many
 * @param staleFactor           schedule intervals without a successful sync before a connector is STALE (and alerts)
 * @param keepRuns              run rows kept per connector
 * @param smb                   SMB client settings
 */
@ConfigurationProperties("verso.connector")
public record ConnectorProperties(
        @DefaultValue("true") boolean syncEnabled,
        @DefaultValue("30s") Duration pollInterval,
        @DefaultValue("10m") Duration lease,
        @DefaultValue("") String allowedNetworks,
        @DefaultValue("50") int maxConnectors,
        @DefaultValue("500") int maxFilesPerRun,
        @DefaultValue("2GB") DataSize maxBytesPerRun,
        @DefaultValue("100000") int maxListedEntries,
        @DefaultValue("32") int maxDepth,
        @DefaultValue("5m") Duration itemRetryBase,
        @DefaultValue("24h") Duration itemRetryMax,
        @DefaultValue("1m") Duration failureRetryBase,
        @DefaultValue("2m") Duration continuationDelay,
        @DefaultValue("20") int deletionSafetyPercent,
        @DefaultValue("20") int deletionSafetyMinimum,
        @DefaultValue("3") int staleFactor,
        @DefaultValue("100") int keepRuns,
        @DefaultValue Smb smb) {

    /**
     * @param connectTimeout  TCP connect
     * @param timeout         one SMB request (read, list) and the socket read timeout
     * @param signingRequired refuse a server that does not sign (tampering on the wire)
     * @param encryptData     SMB3 encryption of the whole session (the server must support SMB 3)
     */
    public record Smb(
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("60s") Duration timeout,
            @DefaultValue("true") boolean signingRequired,
            @DefaultValue("false") boolean encryptData) {

        public Smb {
            if (connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalStateException("verso.connector.smb.connect-timeout must be 1ms..1m");
            }
            if (timeout.isNegative() || timeout.isZero() || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalStateException("verso.connector.smb.timeout must be 1ms..10m");
            }
        }
    }

    public ConnectorProperties {
        // Fail fast at start-up: a public range or a malformed block never reaches a sync.
        NetworkAllowlist.parse(allowedNetworks);
        if (pollInterval.compareTo(Duration.ofSeconds(1)) < 0) throw new IllegalStateException("poll-interval must be >= 1s");
        if (lease.compareTo(Duration.ofMinutes(1)) < 0) throw new IllegalStateException("lease must be >= 1m");
        if (maxConnectors < 1 || maxConnectors > 1000) throw new IllegalStateException("max-connectors must be 1..1000");
        if (maxFilesPerRun < 1 || maxFilesPerRun > 100_000) throw new IllegalStateException("max-files-per-run must be 1..100000");
        if (maxBytesPerRun.toBytes() < 1) throw new IllegalStateException("max-bytes-per-run must be positive");
        if (maxListedEntries < 1 || maxListedEntries > 1_000_000) {
            throw new IllegalStateException("max-listed-entries must be 1..1000000");
        }
        if (maxDepth < 1 || maxDepth > 64) throw new IllegalStateException("max-depth must be 1..64");
        if (deletionSafetyPercent < 0 || deletionSafetyPercent > 100) {
            throw new IllegalStateException("deletion-safety-percent must be 0..100");
        }
        if (deletionSafetyMinimum < 0) throw new IllegalStateException("deletion-safety-minimum must be >= 0");
        if (staleFactor < 2 || staleFactor > 100) throw new IllegalStateException("stale-factor must be 2..100");
        if (keepRuns < 1 || keepRuns > 10_000) throw new IllegalStateException("keep-runs must be 1..10000");
        if (smb == null) smb = new Smb(Duration.ofSeconds(10), Duration.ofSeconds(60), true, false);
    }

    public NetworkAllowlist allowlist() {
        return NetworkAllowlist.parse(allowedNetworks);
    }
}
