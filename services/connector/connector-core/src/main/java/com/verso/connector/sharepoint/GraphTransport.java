package com.verso.connector.sharepoint;

import com.verso.connector.confluence.ConfluenceTransport;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
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
 * One exchange with Microsoft Graph, Entra's token endpoint or a SharePoint download host (ADR-0045), the seam the
 * retry, token and redirect rules are tested through. The real one is Boot's HTTP client with timeouts, no redirect
 * following (the caller decides, and never sends the token along) and an {@link InetAddressFilter} checked on every
 * connect. Bodies are read up to a limit and only for 2xx; more is refused, never buffered.
 */
public interface GraphTransport {

    /** A response: status, the two headers the client uses, and the body of a 2xx (at most the limit). */
    record Response(int status, String location, String retryAfter, byte[] body) {
        @Override
        public String toString() {
            return "Response[status=" + status + "]";
        }
    }

    /**
     * @param authorization the header value, or null for a request that must not carry it (a download redirect)
     * @throws IOException on a connection, TLS or timeout failure; {@link ConfluenceTransport.Refused} for an address
     *                     the connect filter refused; {@link ConfluenceTransport.TooLarge} past the limit
     */
    Response get(URI uri, String authorization, Map<String, String> headers, long maxBytes) throws IOException;

    /** A form POST (the token request); its body holds a secret or a signed assertion and is never logged. */
    Response postForm(URI uri, String form, long maxBytes) throws IOException;

    static GraphTransport http(Duration connectTimeout, Duration readTimeout, InetAddressFilter addresses) {
        HttpClientSettings settings = HttpClientSettings.defaults()
                .withTimeouts(connectTimeout, readTimeout)
                .withRedirects(HttpRedirects.DONT_FOLLOW)
                .withInetAddressFilter(addresses);
        RestClient client = RestClient.builder()
                .requestFactory(ClientHttpRequestFactoryBuilder.detect().build(settings))
                .build();
        return new GraphTransport() {
            @Override
            public Response get(URI uri, String authorization, Map<String, String> headers, long maxBytes)
                    throws IOException {
                return call(() -> client.get().uri(uri)
                        .headers(h -> {
                            if (authorization != null) h.set(HttpHeaders.AUTHORIZATION, authorization);
                            headers.forEach(h::set);
                        })
                        .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_OCTET_STREAM, MediaType.ALL)
                        .exchange((request, response) -> response(response, maxBytes)));
            }

            @Override
            public Response postForm(URI uri, String form, long maxBytes) throws IOException {
                return call(() -> client.post().uri(uri)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .accept(MediaType.APPLICATION_JSON)
                        .body(form)
                        .exchange((request, response) -> response(response, maxBytes)));
            }
        };
    }

    private static Response response(org.springframework.http.client.ClientHttpResponse response, long maxBytes)
            throws IOException {
        HttpHeaders headers = response.getHeaders();
        int status = response.getStatusCode().value();
        byte[] body = status >= 200 && status < 300 ? read(response.getBody(), maxBytes) : new byte[0];
        return new Response(status, headers.getFirst(HttpHeaders.LOCATION), headers.getFirst(HttpHeaders.RETRY_AFTER), body);
    }

    /** The exchange with the client's exceptions turned into the seam's; no message (it may name a host) travels on. */
    private static Response call(java.util.function.Supplier<Response> exchange) throws IOException {
        try {
            return exchange.get();
        } catch (FilteredHostException e) {
            throw new ConfluenceTransport.Refused();
        } catch (RestClientException e) {
            if (e.getCause() instanceof ConfluenceTransport.TooLarge tooLarge) throw tooLarge;
            if (e.getCause() instanceof FilteredHostException) throw new ConfluenceTransport.Refused();
            throw new IOException(e.getClass().getSimpleName(), null);
        }
    }

    private static byte[] read(InputStream in, long maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > maxBytes) throw new ConfluenceTransport.TooLarge();
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
