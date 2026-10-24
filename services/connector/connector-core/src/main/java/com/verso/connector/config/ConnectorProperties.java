package com.verso.connector.config;

import com.verso.connector.domain.HostAllowlist;
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
 * @param confluence            Confluence client and sync settings (ADR-0044)
 * @param externalEgress        internet egress of connectors, off by default (ADR-0044)
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
        @DefaultValue Smb smb,
        @DefaultValue Confluence confluence,
        @DefaultValue ExternalEgress externalEgress) {

    /**
     * The Confluence REST client and sync (ADR-0044).
     *
     * @param connectTimeout      TCP and TLS connect
     * @param readTimeout         one response
     * @param fullListingInterval a complete listing of every page id with its restrictions (deletions, archived pages,
     *                            the restriction sweep that "last modified" cannot see, CONFCLOUD-41375) at least this often
     * @param cursorOverlap       an incremental listing starts this long before the cursor (search index lag, clocks)
     * @param maxAttempts         attempts of one request on 429, 503 or a transport error, then the run stops
     * @param retryBase           backoff without Retry-After: base · 2^(attempt-1), with jitter ...
     * @param maxRetryWait        ... and at most this, also for a longer Retry-After (then the run stops and the connector
     *                            backs off as a whole)
     * @param maxResponseSize     the largest REST response read (JSON and page bodies)
     * @param requireHttps        refuse http:// base URLs (credentials on the wire); only a test stub turns it off
     */
    public record Confluence(
            @DefaultValue("10s") Duration connectTimeout,
            @DefaultValue("60s") Duration readTimeout,
            @DefaultValue("24h") Duration fullListingInterval,
            @DefaultValue("30m") Duration cursorOverlap,
            @DefaultValue("5") int maxAttempts,
            @DefaultValue("1s") Duration retryBase,
            @DefaultValue("60s") Duration maxRetryWait,
            @DefaultValue("32MB") DataSize maxResponseSize,
            @DefaultValue("true") boolean requireHttps) {

        public Confluence {
            if (connectTimeout.isNegative() || connectTimeout.isZero() || connectTimeout.compareTo(Duration.ofMinutes(1)) > 0) {
                throw new IllegalStateException("verso.connector.confluence.connect-timeout must be 1ms..1m");
            }
            if (readTimeout.isNegative() || readTimeout.isZero() || readTimeout.compareTo(Duration.ofMinutes(10)) > 0) {
                throw new IllegalStateException("verso.connector.confluence.read-timeout must be 1ms..10m");
            }
            if (fullListingInterval.compareTo(Duration.ofHours(1)) < 0 || fullListingInterval.compareTo(Duration.ofDays(7)) > 0) {
                throw new IllegalStateException("verso.connector.confluence.full-listing-interval must be 1h..7d");
            }
            if (cursorOverlap.isNegative() || cursorOverlap.compareTo(Duration.ofDays(2)) > 0) {
                throw new IllegalStateException("verso.connector.confluence.cursor-overlap must be 0..2d");
            }
            if (maxAttempts < 1 || maxAttempts > 10) throw new IllegalStateException("verso.connector.confluence.max-attempts must be 1..10");
            if (retryBase.isNegative() || retryBase.isZero() || maxRetryWait.compareTo(retryBase) < 0
                    || maxRetryWait.compareTo(Duration.ofMinutes(5)) > 0) {
                throw new IllegalStateException("verso.connector.confluence retry-base must be > 0 and <= max-retry-wait <= 5m");
            }
            if (maxResponseSize.toBytes() < 1024 || maxResponseSize.toMegabytes() > 256) {
                throw new IllegalStateException("verso.connector.confluence.max-response-size must be 1KB..256MB");
            }
        }

        static Confluence defaults() {
            return new Confluence(Duration.ofSeconds(10), Duration.ofSeconds(60), Duration.ofHours(24),
                    Duration.ofMinutes(30), 5, Duration.ofSeconds(1), Duration.ofSeconds(60), DataSize.ofMegabytes(32),
                    true);
        }
    }

    /**
     * Internet egress of connectors (ADR-0044; llm-rules 1.1 and 1.7). Off by default: a connector reaches only the
     * private allowlist above. Confluence Cloud needs it. Local mode is strict: with verso.ai.mode=local the application
     * refuses to start with it on (ConnectorEgressConfiguration), so local mode keeps its no-egress promise without an
     * exception; cloud mode, which already has an internet route for the chat call, may enable it for listed hosts.
     *
     * @param enabled      VERSO_CONNECTOR_EXTERNAL_EGRESS
     * @param allowedHosts comma-separated host names a connector may reach on the internet, exact or one leading "*."
     *                     label (e.g. *.atlassian.net); empty while enabled refuses to start (VERSO_CONNECTOR_EXTERNAL_HOSTS)
     */
    public record ExternalEgress(
            @DefaultValue("false") boolean enabled,
            @DefaultValue("") String allowedHosts) {

        public ExternalEgress {
            // Fail fast: a malformed or too broad pattern never reaches a sync.
            HostAllowlist.parse(allowedHosts);
            if (enabled && HostAllowlist.parse(allowedHosts).isEmpty()) {
                throw new IllegalStateException("verso.connector.external-egress.allowed-hosts must name the hosts when "
                        + "external egress is enabled");
            }
        }

        /** The hosts a connector may reach on the internet; empty when external egress is off. */
        public HostAllowlist hosts() {
            return enabled ? HostAllowlist.parse(allowedHosts)
                    : HostAllowlist.parse("");
        }
    }

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
        if (confluence == null) confluence = Confluence.defaults();
        if (externalEgress == null) externalEgress = new ExternalEgress(false, "");
    }

    public NetworkAllowlist allowlist() {
        return NetworkAllowlist.parse(allowedNetworks);
    }
}
