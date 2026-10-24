package com.verso.connector.confluence;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.ConfluenceUrl;
import com.verso.connector.domain.RetryPolicy;
import com.verso.connector.smb.SourceException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests of one run against one Confluence site (ADR-0044), with the resilience rules of a remote call (AGENTS.md 5):
 * <ul>
 *   <li>timeouts on connect and read (the transport);</li>
 *   <li>429 and 503 are retried after Retry-After, else exponential backoff with jitter, at most
 *       {@code max-attempts}; a transport failure the same; then the run stops with a fixed code and the connector backs
 *       off as a whole (the run-level circuit: consecutive failed runs wait 1 min · 2^(n-1) up to the schedule);</li>
 *   <li>bulkhead: the sync's own single worker thread, never a request thread;</li>
 *   <li>redirects: followed only to the same origin and context, at most three; another host is refused
 *       (REDIRECT_REFUSED), so the credential never leaves for it;</li>
 *   <li>401 stops the run (SOURCE_AUTH_FAILED); bodies are read up to a limit.</li>
 * </ul>
 * The authorization header lives only in this object for the run; nothing of a request or response reaches a log.
 */
public class ConfluenceClient {

    /** What waits between attempts; tests pass one that records instead of sleeping. */
    public interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private static final int MAX_REDIRECTS = 3;
    private static final int MAX_JSON_DEPTH = 200;

    private final ConfluenceUrl site;
    private final String authorization;
    private final ConfluenceTransport transport;
    private final ConnectorProperties.Confluence settings;
    private final Clock clock;
    private final Sleeper sleeper;
    private final JsonMapper json;
    private Runnable keepAlive = () -> { };

    public ConfluenceClient(ConfluenceUrl site, String authorization, ConfluenceTransport transport,
                            ConnectorProperties.Confluence settings, Clock clock, Sleeper sleeper) {
        this.site = site;
        this.authorization = authorization;
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
        this.json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_DEPTH)
                .maxDocumentLength(settings.maxResponseSize().toBytes())
                .build()).build()).build();
    }

    public ConfluenceUrl site() {
        return site;
    }

    /** Called while waiting for a retry, so a long backoff does not let the run's lease expire. */
    public void keepAlive(Runnable keepAlive) {
        this.keepAlive = keepAlive == null ? () -> { } : keepAlive;
    }

    /**
     * A JSON response with status 2xx.
     *
     * @return null for 403 and 404 (the caller decides what a missing or forbidden item means)
     * @throws SourceException fatal for everything that is not about one item
     */
    public JsonNode json(URI uri) {
        ConfluenceTransport.Response response = send(uri, settings.maxResponseSize().toBytes());
        if (response.status() == 403 || response.status() == 404) return null;
        if (response.status() < 200 || response.status() >= 300) throw SourceException.fatal("SOURCE_ERROR");
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new SourceException("INVALID_RESPONSE", true, e);
        }
    }

    /**
     * An attachment's bytes, at most {@code maxBytes}.
     *
     * @throws SourceException file-level TOO_LARGE, NOT_FOUND, ACCESS_DENIED or REDIRECT_REFUSED (a Cloud attachment
     *                         redirected to the media host); fatal for the rest
     */
    public byte[] bytes(URI uri, long maxBytes) {
        ConfluenceTransport.Response response;
        try {
            response = send(uri, maxBytes);
        } catch (SourceException e) {
            if (e.code().equals("REDIRECT_REFUSED") || e.code().equals("TOO_LARGE")) throw SourceException.file(e.code());
            throw e;
        }
        if (response.status() == 404) throw SourceException.file("NOT_FOUND");
        if (response.status() == 403) throw SourceException.file("ACCESS_DENIED");
        if (response.status() < 200 || response.status() >= 300) throw SourceException.fatal("SOURCE_ERROR");
        return response.body();
    }

    /** The exchange loop: retries, same-origin redirects, and the fatal codes. */
    ConfluenceTransport.Response send(URI uri, long maxBytes) {
        URI current = uri;
        int attempt = 0;
        int redirects = 0;
        while (true) {
            attempt++;
            ConfluenceTransport.Response response;
            try {
                response = transport.get(current, authorization, maxBytes);
            } catch (ConfluenceTransport.TooLarge e) {
                throw SourceException.file("TOO_LARGE");
            } catch (ConfluenceTransport.Refused e) {
                // The address the name resolved to at connect time is outside the rule (a changed DNS answer).
                throw new SourceException("EGRESS_DENIED", true, e);
            } catch (IOException e) {
                if (attempt >= settings.maxAttempts()) throw new SourceException("SOURCE_UNREACHABLE", true, e);
                pause(RetryPolicy.delay(attempt, null, clock.instant(), settings.retryBase(), settings.maxRetryWait(),
                        ThreadLocalRandom.current()::nextDouble), "SOURCE_UNREACHABLE");
                continue;
            }
            int status = response.status();
            if (status == 429 || status == 503) {
                String code = status == 429 ? "RATE_LIMITED" : "SOURCE_UNAVAILABLE";
                if (attempt >= settings.maxAttempts()) throw SourceException.fatal(code);
                pause(RetryPolicy.delay(attempt, response.retryAfter(), clock.instant(), settings.retryBase(),
                        settings.maxRetryWait(), ThreadLocalRandom.current()::nextDouble), code);
                continue;
            }
            if (status >= 300 && status < 400 && status != 304) {
                URI target = target(current, response.location());
                if (target == null || !site.sameOrigin(target) || ++redirects > MAX_REDIRECTS) {
                    throw SourceException.fatal("REDIRECT_REFUSED");
                }
                current = target;
                attempt = 0;
                continue;
            }
            if (status == 401) throw SourceException.fatal("SOURCE_AUTH_FAILED");
            return response;
        }
    }

    private void pause(Duration wait, String code) {
        // The server asked for longer than the cap: give up now, the connector-level backoff takes over.
        if (wait == null) throw SourceException.fatal(code);
        try {
            keepAlive.run();
            sleeper.sleep(wait);
            keepAlive.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SourceException("SOURCE_UNREACHABLE", true, e);
        }
    }

    private static URI target(URI current, String location) {
        if (location == null || location.isBlank() || location.length() > 4096) return null;
        try {
            return current.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "ConfluenceClient";
    }
}
