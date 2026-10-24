package com.verso.connector.confluence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.ConfluenceUrl;
import com.verso.connector.domain.SyncPlanner.ListedFile;
import com.verso.connector.smb.FileSource.Listing;
import com.verso.connector.smb.SourceException;
import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

/**
 * The Confluence client and source against a scripted transport (ADR-0044): 429 is retried after Retry-After and then
 * gives up, a redirect stays on the site or is refused, restricted pages (own or inherited) are marked, a restriction
 * check that fails leaves the page out and the listing incomplete, an incremental listing asks for a window and
 * reports itself, and attachments are listed only as PDF/DOCX of a known download path.
 */
class ConfluenceSourceTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");
    private static final String AUTH = "Bearer pat-" + UUID.randomUUID();
    private final ConfluenceUrl site = ConfluenceUrl.parse("https://wiki.corp.local/confluence", false);
    private final List<Duration> sleeps = new ArrayList<>();
    private final List<String> requested = new ArrayList<>();
    private final List<String> authorizations = new ArrayList<>();

    @Test
    void send_whenThrottled_waitsForRetryAfterThenSucceeds_andGivesUpAfterMaxAttempts() {
        int[] calls = {0};
        ConfluenceClient client = client(uri -> ++calls[0] < 3 ? response(429, null, "2", "") : json("{\"ok\":true}"));

        assertThat(client.json(site.api("/rest/api/space")).path("ok").asBoolean()).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(2), Duration.ofSeconds(2));
        assertThat(authorizations).allMatch(AUTH::equals);

        ConfluenceClient throttled = client(uri -> response(429, null, null, ""));
        assertThatThrownBy(() -> throttled.json(site.api("/rest/api/space")))
                .isInstanceOfSatisfying(SourceException.class, e -> {
                    assertThat(e.code()).isEqualTo("RATE_LIMITED");
                    assertThat(e.isFatal()).isTrue();
                });
        assertThat(sleeps).hasSize(2 + 4);
        for (int n = 1; n <= 4; n++) {
            assertThat(sleeps.get(1 + n)).as("exponential with jitter: base·2^(n-1)/2 .. base·2^(n-1)")
                    .isBetween(Duration.ofMillis(500L << (n - 1)), Duration.ofMillis(1000L << (n - 1)));
        }

        ConfluenceClient tooLong = client(uri -> response(429, null, "3600", ""));
        assertThatThrownBy(() -> tooLong.json(site.api("/rest/api/space"))).hasMessage("RATE_LIMITED");
    }

    @Test
    void send_whenRedirected_followsOnlyTheSameSite() {
        ConfluenceClient same = client(uri -> uri.getPath().endsWith("/old")
                ? response(301, "/confluence/rest/api/new", null, "") : json("{\"moved\":1}"));
        assertThat(same.json(site.api("/rest/api/old")).path("moved").asInt()).isEqualTo(1);

        List<String> other = List.of("https://evil.example/rest/api/x", "http://wiki.corp.local/confluence/rest/api/x",
                "https://wiki.corp.local:8443/confluence/rest/api/x", "https://wiki.corp.local/elsewhere");
        for (String target : other) {
            requested.clear();
            ConfluenceClient client = client(uri -> response(302, target, null, ""));
            assertThatThrownBy(() -> client.json(site.api("/rest/api/x"))).as(target).hasMessage("REDIRECT_REFUSED");
            assertThat(requested).as("the other host is never asked").hasSize(1);
        }
        ConfluenceClient attachment = client(uri -> response(302, "https://api.media.atlassian.com/file/1", null, ""));
        assertThatThrownBy(() -> attachment.bytes(site.resolve("/download/attachments/1/a.pdf"), 100))
                .isInstanceOfSatisfying(SourceException.class, e -> assertThat(e.isFatal()).isFalse())
                .hasMessage("REDIRECT_REFUSED");
    }

    @Test
    void send_whenUnauthorizedOrUnreachable_stopsTheRunWithAFixedCode() {
        assertThatThrownBy(() -> client(uri -> response(401, null, null, "")).json(site.api("/rest/api/x")))
                .hasMessage("SOURCE_AUTH_FAILED");
        ConfluenceClient down = client(uri -> {
            throw new IOException("connect failed to wiki.corp.local");
        });
        assertThatThrownBy(() -> down.json(site.api("/rest/api/x"))).hasMessage("SOURCE_UNREACHABLE");
        assertThat(client(uri -> response(404, null, null, "")).json(site.api("/rest/api/x"))).isNull();
    }

    /**
     * The real transport (Boot's client) applies the connect filter: a server on an address the deployment's rule refuses
     * is never reached, it does not follow redirects itself, and the body limit holds. Loopback only, no Docker.
     */
    @Test
    void http_whenTheAddressIsRefusedOrTheBodyTooLarge_failsWithoutReachingOrBuffering() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress(java.net.InetAddress.getLoopbackAddress(), 0), 0);
        List<String> hits = new java.util.concurrent.CopyOnWriteArrayList<>();
        server.createContext("/", exchange -> {
            hits.add(exchange.getRequestURI().getPath());
            byte[] body = "x".repeat(2_000).getBytes(StandardCharsets.UTF_8);
            if (exchange.getRequestURI().getPath().equals("/moved")) {
                exchange.getResponseHeaders().add("Location", "http://other.invalid/");
                exchange.sendResponseHeaders(302, -1);
            } else {
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
            exchange.close();
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            ConfluenceTransport refused = ConfluenceTransport.http(Duration.ofSeconds(2), Duration.ofSeconds(2),
                    org.springframework.boot.http.client.InetAddressFilter.externalAddresses());
            assertThatThrownBy(() -> refused.get(base.resolve("/x"), AUTH, 10_000)).isInstanceOf(ConfluenceTransport.Refused.class)
                    .hasMessageNotContaining("127.0.0.1");
            assertThat(hits).as("the refused address was never connected to").isEmpty();

            ConfluenceTransport allowed = ConfluenceTransport.http(Duration.ofSeconds(2), Duration.ofSeconds(2),
                    org.springframework.boot.http.client.InetAddressFilter.all());
            assertThat(allowed.get(base.resolve("/moved"), AUTH, 10_000).status()).as("no redirect followed").isEqualTo(302);
            assertThatThrownBy(() -> allowed.get(base.resolve("/big"), AUTH, 1_000))
                    .isInstanceOf(ConfluenceTransport.TooLarge.class);
            assertThat(allowed.get(base.resolve("/ok"), AUTH, 10_000).body()).hasSize(2_000);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void list_whenPagesAreRestricted_marksThemIncludingInheritedRestrictions() {
        ConfluenceFileSource source = source(settings(true, null, false), uri -> {
            String path = uri.getPath();
            if (path.endsWith("/content/search")) {
                return json("{\"results\":[" + page(10, 3, "Açık sayfa", "[]") + "," + page(11, 1, "Kısıtlı", "[]") + ","
                        + page(12, 2, "Alt sayfa", "[{\"id\":\"11\"}]") + "],\"_links\":{}}");
            }
            if (path.endsWith("/content/11/restriction/byOperation/read")) {
                return json("{\"restrictions\":{\"user\":{\"results\":[{\"type\":\"known\"}],\"size\":1},"
                        + "\"group\":{\"results\":[],\"size\":0}}}");
            }
            if (path.contains("/restriction/byOperation/read")) return json(open());
            return response(404, null, null, "");
        });

        Listing listing = source.list(() -> { });

        assertThat(listing.complete()).isTrue();
        assertThat(listing.incremental()).isFalse();
        assertThat(listing.files()).extracting(ListedFile::path, ListedFile::restricted, ListedFile::sizeBytes)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("pages/10", false, 3L),
                        org.assertj.core.groups.Tuple.tuple("pages/11", true, 1L),
                        org.assertj.core.groups.Tuple.tuple("pages/12", true, 2L));
        assertThat(listing.highWater()).isEqualTo(Instant.parse("2026-10-20T08:00:00Z"));
        assertThat(source.documentName(listing.files().getFirst())).isEqualTo("Açık sayfa.md");
        assertThat(requested.stream().filter(p -> p.contains("/content/11/restriction"))).as("cached").hasSize(1);
        String cql = URLDecoder.decode(requested.getFirst().substring(requested.getFirst().indexOf("cql=") + 4)
                .split("&")[0], StandardCharsets.UTF_8);
        assertThat(cql).isEqualTo("type = page AND space in (\"HR\") ORDER BY created ASC");
    }

    @Test
    void list_whenARestrictionCheckFails_leavesThePageOutAndTheListingIncomplete() {
        ConfluenceFileSource source = source(settings(true, null, false), uri -> {
            String path = uri.getPath();
            if (path.endsWith("/content/search")) {
                return json("{\"results\":[" + page(10, 1, "A", "[]") + "," + page(20, 1, "B", "[]") + "]}");
            }
            if (path.endsWith("/content/20/restriction/byOperation/read")) return response(403, null, null, "");
            return json(open());
        });

        Listing listing = source.list(() -> { });

        assertThat(listing.files()).extracting(ListedFile::path).containsExactly("pages/10");
        assertThat(listing.complete()).isFalse();
        assertThat(listing.problem()).isEqualTo("RESTRICTION_CHECK_FAILED");
    }

    @Test
    void list_whenACursorExists_asksForAWindowOnly_andFollowsNextLinksOnTheSite() {
        Instant cursor = NOW.minus(Duration.ofHours(2));
        ConfluenceFileSource source = source(settings(false, cursor, false), uri -> {
            if (uri.getRawQuery() != null && uri.getRawQuery().contains("cursor=2")) {
                return json("{\"results\":[" + page(31, 1, "İkinci", "[]") + "],\"_links\":{}}");
            }
            return json("{\"results\":[" + page(30, 1, "Birinci", "[]") + "],\"_links\":{\"next\":"
                    + "\"/rest/api/content/search?cursor=2&limit=50\"}}");
        });

        Listing listing = source.list(() -> { });

        assertThat(listing.incremental()).isTrue();
        assertThat(listing.files()).extracting(ListedFile::path).containsExactly("pages/30", "pages/31");
        assertThat(requested).as("skipRestricted off: no checks").noneMatch(p -> p.contains("restriction"));
        assertThat(URLDecoder.decode(requested.getFirst(), StandardCharsets.UTF_8))
                .contains("lastmodified >= now(\"-150m\")");
        assertThat(requested.get(1)).startsWith("/confluence/rest/api/content/search?cursor=2");
    }

    @Test
    void listAndRead_whenAttachmentsAreOn_listsPdfAndDocxOnly_andConvertsPagesToMarkdown() {
        ConfluenceFileSource source = source(settings(false, null, true), uri -> {
            String path = uri.getPath();
            if (path.endsWith("/content/search")) return json("{\"results\":[" + page(40, 5, "Sayfa", "[]") + "]}");
            if (path.endsWith("/content/40/child/attachment")) {
                return json("{\"results\":[" + attachment("att501", "rapor.pdf", 1234, "/download/attachments/40/rapor.pdf")
                        + "," + attachment("att502", "resim.png", 10, "/download/attachments/40/resim.png") + ","
                        + attachment("att503", "kaçak.docx", 10, "https://evil.example/x.docx") + "]}");
            }
            if (path.endsWith("/content/40")) {
                return json("{\"title\":\"Sayfa\",\"body\":{\"storage\":{\"value\":\"<h2>Bölüm</h2><p>Metin</p>\"}}}");
            }
            if (path.endsWith("/download/attachments/40/rapor.pdf")) return response(200, null, null, "%PDF-1.7");
            return response(404, null, null, "");
        });

        Listing listing = source.list(() -> { });

        assertThat(listing.files()).extracting(ListedFile::path).containsExactly("pages/40", "attachments/40/501");
        assertThat(new String(source.read("pages/40", 10_000), StandardCharsets.UTF_8)).isEqualTo("# Sayfa\n\n## Bölüm\n\nMetin");
        assertThat(new String(source.read("attachments/40/501", 10_000), StandardCharsets.UTF_8)).isEqualTo("%PDF-1.7");
        assertThat(source.documentName(listing.files().get(1))).isEqualTo("rapor.pdf");
        assertThatThrownBy(() -> source.read("pages/40", 5)).hasMessage("TOO_LARGE");
        assertThatThrownBy(() -> source.read("pages/../admin", 5)).hasMessage("NOT_FOUND");
    }

    private ConfluenceFileSource source(ConfluenceRunSettings settings, ScriptedServer server) {
        return new ConfluenceFileSource(settings, client(server), Duration.ofMinutes(30), 100_000,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private ConfluenceClient client(ScriptedServer server) {
        ConfluenceTransport transport = (uri, authorization, maxBytes) -> {
            requested.add(uri.getRawPath() + (uri.getRawQuery() == null ? "" : "?" + uri.getRawQuery()));
            authorizations.add(authorization);
            ConfluenceTransport.Response response = server.answer(uri);
            if (response.body().length > maxBytes) throw new ConfluenceTransport.TooLarge();
            return response;
        };
        ConnectorProperties.Confluence settings = new ConnectorProperties.Confluence(Duration.ofSeconds(1),
                Duration.ofSeconds(1), Duration.ofHours(24), Duration.ofMinutes(30), 5, Duration.ofSeconds(1),
                Duration.ofSeconds(60), DataSize.ofMegabytes(1), true);
        return new ConfluenceClient(site, AUTH, transport, settings, Clock.fixed(NOW, ZoneOffset.UTC), sleeps::add);
    }

    @FunctionalInterface
    private interface ScriptedServer {
        ConfluenceTransport.Response answer(URI uri) throws IOException;
    }

    private static ConfluenceRunSettings settings(boolean skipRestricted, Instant cursor, boolean attachments) {
        return new ConfluenceRunSettings(UUID.randomUUID(), "https://wiki.corp.local/confluence", "DATA_CENTER",
                List.of("HR"), null, null, attachments, skipRestricted, 50, null, "SECRET_CONNECTOR_CONFLUENCE_TOKEN",
                cursor == null, cursor);
    }

    private static String page(long id, int version, String title, String ancestors) {
        return "{\"id\":\"" + id + "\",\"type\":\"page\",\"title\":\"" + title + "\",\"version\":{\"number\":" + version
                + ",\"when\":\"2026-10-20T11:00:00.000+03:00\"},\"ancestors\":" + ancestors + "}";
    }

    private static String attachment(String id, String name, long size, String download) {
        return "{\"id\":\"" + id + "\",\"title\":\"" + name + "\",\"extensions\":{\"fileSize\":" + size + "},"
                + "\"version\":{\"when\":\"2026-10-20T08:00:00.000Z\"},\"_links\":{\"download\":\"" + download + "\"}}";
    }

    private static String open() {
        return "{\"restrictions\":{\"user\":{\"results\":[],\"size\":0},\"group\":{\"results\":[],\"size\":0}}}";
    }

    private static ConfluenceTransport.Response json(String body) {
        return response(200, null, null, body);
    }

    private static ConfluenceTransport.Response response(int status, String location, String retryAfter, String body) {
        return new ConfluenceTransport.Response(status, location, retryAfter, body.getBytes(StandardCharsets.UTF_8));
    }
}
