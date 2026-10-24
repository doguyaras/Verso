package com.verso.connector.sharepoint;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ConfluenceClient;
import com.verso.connector.confluence.ConfluenceTransport;
import com.verso.connector.domain.RetryPolicy;
import com.verso.connector.smb.SourceException;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * App-only access tokens for Microsoft Graph from Entra ID (ADR-0045): OAuth 2.0 client credentials against
 * {@code <authority>/<tenant>/oauth2/v2.0/token} with the scope {@code https://graph.microsoft.com/.default}, so the app
 * gets exactly the application permissions an admin granted it (Sites.Selected plus a per-site grant, recommended).
 * The client proves itself with a signed assertion (certificate) or the client secret.
 *
 * <p>The token lives in this object for one run, is refreshed five minutes before it expires or once after a 401, and
 * never reaches a log, the database or a response. Token requests follow the same retry rule as Graph requests (429
 * and 503 after Retry-After, at most max-attempts); a refused credential stops the run with SOURCE_AUTH_FAILED.
 */
final class EntraTokens implements AutoCloseable {

    /** How the app authenticates; the secret is a char array, zeroed when the run ends. */
    sealed interface Credential permits Secret, Assertion {
    }

    record Secret(char[] value) implements Credential {
        @Override
        public String toString() {
            return "Secret[redacted]";
        }
    }

    record Assertion(ClientAssertion.Certificate certificate, String algorithm) implements Credential {
        @Override
        public String toString() {
            return "Assertion[redacted]";
        }
    }

    private static final Duration REFRESH_BEFORE = Duration.ofMinutes(5);
    private static final long MAX_TOKEN_RESPONSE = 64 * 1024;
    /** A bearer token: the characters of a JWT or an opaque token, never a line break (header injection). */
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{16,16384}");
    private static final String ASSERTION_TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";

    private final URI endpoint;
    private final String clientId;
    private final String scope;
    private final Credential credential;
    private final GraphTransport transport;
    private final ConnectorProperties.SharePoint settings;
    private final Clock clock;
    private final ConfluenceClient.Sleeper sleeper;
    private final JsonMapper json = JsonMapper.builder().build();
    private Runnable keepAlive = () -> { };
    private String token;
    private Instant expiresAt = Instant.MIN;

    EntraTokens(URI endpoint, String clientId, String scope, Credential credential, GraphTransport transport,
                ConnectorProperties.SharePoint settings, Clock clock, ConfluenceClient.Sleeper sleeper) {
        this.endpoint = endpoint;
        this.clientId = clientId;
        this.scope = scope;
        this.credential = credential;
        this.transport = transport;
        this.settings = settings;
        this.clock = clock;
        this.sleeper = sleeper;
    }

    /** {@code <authority>/<tenant>/oauth2/v2.0/token}; the tenant is a GUID (checked by the API and the database). */
    static URI endpoint(URI authority, String tenantId) {
        return URI.create(authority.toString() + "/" + tenantId + "/oauth2/v2.0/token");
    }

    void keepAlive(Runnable keepAlive) {
        this.keepAlive = keepAlive == null ? () -> { } : keepAlive;
    }

    /** The Authorization header value; a new token when there is none or it expires within five minutes. */
    String bearer() {
        if (token == null || !clock.instant().isBefore(expiresAt.minus(REFRESH_BEFORE))) fetch();
        return "Bearer " + token;
    }

    /** After a 401: the next {@link #bearer()} asks for a new token. */
    void invalidate() {
        token = null;
    }

    private void fetch() {
        int attempt = 0;
        while (true) {
            attempt++;
            GraphTransport.Response response;
            try {
                response = transport.postForm(endpoint, form(), MAX_TOKEN_RESPONSE);
            } catch (ConfluenceTransport.Refused e) {
                throw new SourceException("EGRESS_DENIED", true, e);
            } catch (ConfluenceTransport.TooLarge e) {
                throw new SourceException("INVALID_RESPONSE", true, e);
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
            // 400 invalid_client / unauthorized_client, 401: the app, its secret or certificate, or the tenant is wrong.
            if (status == 400 || status == 401 || status == 403) throw SourceException.fatal("SOURCE_AUTH_FAILED");
            if (status < 200 || status >= 300) throw SourceException.fatal("SOURCE_ERROR");
            accept(response.body());
            return;
        }
    }

    private void accept(byte[] body) {
        JsonNode answer;
        try {
            answer = json.readTree(body);
        } catch (JacksonException e) {
            throw new SourceException("INVALID_RESPONSE", true, e);
        }
        String value = answer.path("access_token").asString("");
        long expiresIn = answer.path("expires_in").asLong(0);
        if (!TOKEN.matcher(value).matches() || expiresIn <= 0) throw SourceException.fatal("INVALID_RESPONSE");
        token = value;
        expiresAt = clock.instant().plusSeconds(Math.min(expiresIn, 86_400));
    }

    /** The request body; holds the secret or the signed assertion only for the duration of the call. */
    private String form() {
        StringBuilder form = new StringBuilder()
                .append("grant_type=client_credentials")
                .append("&client_id=").append(encode(clientId))
                .append("&scope=").append(encode(scope));
        switch (credential) {
            case Secret secret -> form.append("&client_secret=")
                    .append(URLEncoder.encode(new String(secret.value()), StandardCharsets.UTF_8));
            case Assertion assertion -> form.append("&client_assertion_type=").append(encode(ASSERTION_TYPE))
                    .append("&client_assertion=").append(ClientAssertion.sign(clientId, endpoint,
                            assertion.certificate(), assertion.algorithm(), clock.instant()));
        }
        return form.toString();
    }

    private void pause(int attempt, String retryAfter, String code) {
        Duration wait = RetryPolicy.delay(attempt, retryAfter, clock.instant(), settings.retryBase(),
                settings.maxRetryWait(), ThreadLocalRandom.current()::nextDouble);
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

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public void close() {
        token = null;
        if (credential instanceof Secret secret) Arrays.fill(secret.value(), '\0');
    }

    @Override
    public String toString() {
        return "EntraTokens[redacted]";
    }
}
