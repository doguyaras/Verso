package com.verso.connector.sharepoint;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ConfluenceClient;
import com.verso.connector.confluence.ConfluenceTransport;
import com.verso.connector.domain.RetryPolicy;
import com.verso.connector.smb.SourceException;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Requests of one run against Microsoft Graph (ADR-0045), with the resilience rules of a remote call (AGENTS.md 5):
 * <ul>
 *   <li>timeouts on connect and read (the transport); bulkhead: the sync's own single worker thread;</li>
 *   <li>throttling: 429, 503 and 504 are retried after Retry-After (seconds or an HTTP date), else exponential backoff
 *       with jitter, at most {@code max-attempts}; a wait longer than {@code max-retry-wait} or exhausted attempts
 *       stop the run (RATE_LIMITED, SOURCE_UNAVAILABLE) and the connector backs off as a whole (the run-level circuit);
 *       the lease is renewed while waiting;</li>
 *   <li>401: one new token, then SOURCE_AUTH_FAILED; 410 Gone on a delta request: {@link ResyncRequired};</li>
 *   <li>redirects: a JSON request never follows one (REDIRECT_REFUSED: the token would go along). A file download
 *       follows Graph's single redirect to the pre-authenticated SharePoint URL only when
 *       {@link SharePointEgress#downloadAllowed} says so, and without the Authorization header.</li>
 * </ul>
 * Nothing of a request or response (URL, token, body) reaches a log.
 */
public class GraphClient {

    /** Graph answered 410 Gone to a delta request: the delta link expired, the library must be listed from scratch. */
    public static final class ResyncRequired extends RuntimeException {
        ResyncRequired() {
            super("RESYNC_REQUIRED", null, false, false);
        }
    }

    private static final int MAX_JSON_DEPTH = 200;

    private final SharePointEgress egress;
    private final EntraTokens tokens;
    private final GraphTransport transport;
    private final ConnectorProperties.SharePoint settings;
    private final Clock clock;
    private final ConfluenceClient.Sleeper sleeper;
    private final JsonMapper json;
    private Runnable keepAlive = () -> { };

    GraphClient(SharePointEgress egress, EntraTokens tokens, GraphTransport transport,
                ConnectorProperties.SharePoint settings, Clock clock, ConfluenceClient.Sleeper sleeper) {
        this.egress = egress;
        this.tokens = tokens;
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
        this.json = JsonMapper.builder(JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder()
                .maxNestingDepth(MAX_JSON_DEPTH)
                .maxDocumentLength(settings.maxResponseSize().toBytes())
                .build()).build()).build();
    }

    void keepAlive(Runnable keepAlive) {
        this.keepAlive = keepAlive == null ? () -> { } : keepAlive;
        tokens.keepAlive(this.keepAlive);
    }

    SharePointEgress egress() {
        return egress;
    }

    /** A Graph URL under the configured base ({@code /v1.0}); the path is built from validated ids only. */
    URI graph(String pathAndQuery) {
        return URI.create(egress.graph().toString() + pathAndQuery);
    }

    /**
     * A JSON response with status 2xx.
     *
     * @return null for 403 and 404 (the caller decides what a missing or forbidden item means)
     * @throws ResyncRequired on 410 Gone
     * @throws SourceException fatal for everything that is not about one item
     */
    JsonNode json(URI uri) {
        GraphTransport.Response response;
        try {
            response = send(uri, true, settings.maxResponseSize().toBytes());
        } catch (SourceException e) {
            // A listing page past the limit is no file to skip: the run cannot read its source.
            if (!e.isFatal()) throw new SourceException("INVALID_RESPONSE", true, e);
            throw e;
        }
        int status = response.status();
        if (status == 403 || status == 404) return null;
        if (status == 410) throw new ResyncRequired();
        if (status >= 300 && status < 400) throw SourceException.fatal("REDIRECT_REFUSED");
        if (status < 200 || status >= 300) throw SourceException.fatal("SOURCE_ERROR");
        try {
            return json.readTree(response.body());
        } catch (JacksonException e) {
            throw new SourceException("INVALID_RESPONSE", true, e);
        }
    }

    /**
     * A file's bytes through {@code /drives/{d}/items/{i}/content}, at most {@code maxBytes}.
     *
     * @throws SourceException file-level TOO_LARGE, NOT_FOUND, ACCESS_DENIED, REDIRECT_REFUSED or READ_FAILED; fatal
     *                         for the rest
     */
    byte[] download(URI content, long maxBytes) {
        GraphTransport.Response first = send(content, true, maxBytes);
        int status = first.status();
        if (status >= 200 && status < 300) return first.body();
        if (status == 404) throw SourceException.file("NOT_FOUND");
        if (status == 403) throw SourceException.file("ACCESS_DENIED");
        if (status < 300 || status >= 400) throw SourceException.file("READ_FAILED");
        URI target = target(content, first.location());
        // The pre-authenticated SharePoint URL, nowhere else; the token never goes along.
        if (!egress.downloadAllowed(target)) throw SourceException.file("REDIRECT_REFUSED");
        GraphTransport.Response second = send(target, false, maxBytes);
        int code = second.status();
        if (code >= 200 && code < 300) return second.body();
        if (code >= 300 && code < 400) throw SourceException.file("REDIRECT_REFUSED");
        if (code == 404) throw SourceException.file("NOT_FOUND");
        if (code == 401 || code == 403) throw SourceException.file("ACCESS_DENIED");
        throw SourceException.file("READ_FAILED");
    }

    /** The exchange loop: throttling, transport retries and one token refresh. */
    GraphTransport.Response send(URI uri, boolean authorized, long maxBytes) {
        int attempt = 0;
        boolean refreshed = false;
        while (true) {
            attempt++;
            GraphTransport.Response response;
            try {
                response = transport.get(uri, authorized ? tokens.bearer() : null, Map.of(), maxBytes);
            } catch (ConfluenceTransport.TooLarge e) {
                throw SourceException.file("TOO_LARGE");
            } catch (ConfluenceTransport.Refused e) {
                // The address the name resolved to at connect time is outside the rule (a changed DNS answer).
                throw new SourceException("EGRESS_DENIED", true, e);
            } catch (IOException e) {
                if (attempt >= settings.maxAttempts()) throw new SourceException("SOURCE_UNREACHABLE", true, e);
                pause(attempt, null, "SOURCE_UNREACHABLE");
                continue;
            }
            int status = response.status();
            if (status == 429 || status == 503 || status == 504) {
                String code = status == 429 ? "RATE_LIMITED" : "SOURCE_UNAVAILABLE";
                if (attempt >= settings.maxAttempts()) throw SourceException.fatal(code);
                pause(attempt, response.retryAfter(), code);
                continue;
            }
            if (status == 401 && authorized) {
                if (refreshed) throw SourceException.fatal("SOURCE_AUTH_FAILED");
                tokens.invalidate();
                refreshed = true;
                attempt--;
                continue;
            }
            return response;
        }
    }

    private void pause(int attempt, String retryAfter, String code) {
        Duration wait = RetryPolicy.delay(attempt, retryAfter, clock.instant(), settings.retryBase(),
                settings.maxRetryWait(), ThreadLocalRandom.current()::nextDouble);
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
        if (location == null || location.isBlank() || location.length() > 8192) return null;
        try {
            return current.resolve(new URI(location.strip()));
        } catch (URISyntaxException | IllegalArgumentException e) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "GraphClient";
    }
}
