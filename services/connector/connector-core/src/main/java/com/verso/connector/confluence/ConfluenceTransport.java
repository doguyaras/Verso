package com.verso.connector.confluence;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.FilteredHostException;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * One GET to a Confluence site (ADR-0044), the seam the retry and redirect rules are tested through. The real one is
 * Boot's HTTP client with timeouts, no redirect following (the caller decides, same origin only) and an
 * {@link InetAddressFilter} checked on every connect, so even a changed DNS answer cannot reach an address outside the
 * deployment's rule. The response body is read up to a limit; more is refused, never buffered.
 */
public interface ConfluenceTransport {

    /** A response: status, the two headers the client uses, and the body (at most the limit). */
    record Response(int status, String location, String retryAfter, byte[] body) {
    }

    /** The body was longer than the limit. */
    final class TooLarge extends IOException {
        public TooLarge() {
            super("TOO_LARGE", null);
        }
    }

    /** The connect filter refused the address (it is outside the deployment's rule): never retried. */
    final class Refused extends IOException {
        public Refused() {
            super("ADDRESS_REFUSED", null);
        }
    }

    /**
     * @throws IOException on a connection, TLS or timeout failure, {@link Refused} for an address the filter refused,
     *                     and {@link TooLarge}
     */
    Response get(URI uri, String authorization, long maxBytes) throws IOException;

    /** Boot's client; the authorization header value is set per request and never logged. */
    static ConfluenceTransport http(Duration connectTimeout, Duration readTimeout, InetAddressFilter addresses) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(connectTimeout, readTimeout)
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withInetAddressFilter(addresses);
        RestClient client = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
        return (uri, authorization, maxBytes) -> {
            try {
                return client.get().uri(uri)
                        .header(HttpHeaders.AUTHORIZATION, authorization)
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                        .exchange((request, response) -> {
                            HttpHeaders headers = response.getHeaders();
                            int status = response.getStatusCode().value();
                            byte[] body = status >= 200 && status < 300 ? read(response.getBody(), maxBytes) : new byte[0];
                            return new Response(status, headers.getFirst(HttpHeaders.LOCATION),
                                    headers.getFirst(HttpHeaders.RETRY_AFTER), body);
                        });
            } catch (FilteredHostException e) {
                // Its message names the host: dropped, only the fact travels on.
                throw new Refused();
            } catch (RestClientException e) {
                if (e.getCause() instanceof TooLarge tooLarge) throw tooLarge;
                if (e.getCause() instanceof FilteredHostException) throw new Refused();
                throw new IOException(e.getClass().getSimpleName(), null);
            }
        };
    }

    private static byte[] read(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) throw new TooLarge();
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
