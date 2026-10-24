package com.verso.connector.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The pure Confluence rules (ADR-0044): the CQL builder and its quoting, the 429 backoff, the site URL and the
 * external host allowlist.
 */
class ConfluenceRulesTest {

    private static final Instant NOW = Instant.parse("2026-10-24T10:00:00Z");

    @Test
    void pages_whenFiltersAreGiven_buildsQuotedCql() {
        assertThat(CqlBuilder.pages(List.of("HR", "~jdoe"), "politika", 123L, null))
                .isEqualTo("type = page AND space in (\"HR\",\"~jdoe\") AND label = \"politika\" AND ancestor = 123 "
                        + "ORDER BY created ASC");
        assertThat(CqlBuilder.pages(List.of("IT"), null, null, 95L))
                .isEqualTo("type = page AND space in (\"IT\") AND lastmodified >= now(\"-95m\") ORDER BY created ASC");
    }

    @Test
    void pages_whenAValueTriesToCloseTheString_isRefusedOrEscaped() {
        assertThatThrownBy(() -> CqlBuilder.pages(List.of("HR\") OR space = (\"X"), null, null, null))
                .hasMessage("SPACE_KEYS_INVALID");
        assertThatThrownBy(() -> CqlBuilder.pages(List.of("HR"), "a\" OR label = \"b", null, null))
                .hasMessage("LABEL_INVALID");
        assertThatThrownBy(() -> CqlBuilder.pages(List.of(), null, null, null)).hasMessage("SPACE_KEYS_INVALID");
        assertThatThrownBy(() -> CqlBuilder.pages(List.of("HR"), null, 0L, null)).hasMessage("ANCESTOR_INVALID");
        assertThat(CqlBuilder.quote("a\"b\\c\nd")).isEqualTo("\"a\\\"b\\\\cd\"");
    }

    @Test
    void windowMinutes_whenCursorIsBehind_coversItPlusTheOverlapRoundedUp() {
        assertThat(CqlBuilder.windowMinutes(NOW.minusSeconds(3_601), NOW, Duration.ofMinutes(30))).isEqualTo(91);
        assertThat(CqlBuilder.windowMinutes(NOW.plusSeconds(600), NOW, Duration.ZERO)).as("clock skew").isEqualTo(1);
    }

    @Test
    void delay_whenRetryAfterIsGiven_followsIt_elseBacksOffWithJitter_andGivesUpBeyondTheCap() {
        Duration base = Duration.ofSeconds(1);
        Duration max = Duration.ofSeconds(60);
        assertThat(RetryPolicy.delay(1, "7", NOW, base, max, () -> 0.5)).isEqualTo(Duration.ofSeconds(7));
        assertThat(RetryPolicy.delay(1, "Sat, 24 Oct 2026 10:00:30 GMT", NOW, base, max, () -> 0.5))
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(RetryPolicy.delay(1, "Sat, 24 Oct 2026 09:00:00 GMT", NOW, base, max, () -> 0.5)).isZero();
        assertThat(RetryPolicy.delay(1, "120", NOW, base, max, () -> 0.5)).as("longer than the cap").isNull();
        assertThat(RetryPolicy.delay(1, null, NOW, base, max, () -> 0)).isEqualTo(Duration.ofMillis(500));
        assertThat(RetryPolicy.delay(3, "soon", NOW, base, max, () -> 1)).isEqualTo(Duration.ofSeconds(4));
        assertThat(RetryPolicy.delay(30, null, NOW, base, max, () -> 1)).isEqualTo(max);
    }

    @Test
    void parse_whenTheUrlIsStrict_keepsHostPortAndContext() {
        ConfluenceUrl cloud = ConfluenceUrl.parse("https://Acme.atlassian.net/wiki/", false);
        assertThat(cloud.text()).isEqualTo("https://acme.atlassian.net/wiki");
        assertThat(cloud.port()).isEqualTo(443);
        assertThat(cloud.api("/rest/api/content/1")).hasToString("https://acme.atlassian.net/wiki/rest/api/content/1");
        assertThat(ConfluenceUrl.parse("https://10.1.2.3:8443", false).text()).isEqualTo("https://10.1.2.3:8443");
        for (String bad : List.of("http://wiki.corp", "https://user:pw@wiki.corp", "https://wiki.corp/?a=1",
                "https://wiki.corp/#x", "https://wiki.corp/a/../b", "ftp://wiki.corp", "https://", "wiki.corp",
                "https://wiki.corp:0", "https://wiki..corp")) {
            assertThatThrownBy(() -> ConfluenceUrl.parse(bad, false)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ConfluenceUrl.parse("http://wiki.corp", true).port()).as("test stub only").isEqualTo(80);
    }

    @Test
    void resolveAndSameOrigin_whenTheServerSendsLinks_staysOnTheSiteAndUnderTheContext() {
        ConfluenceUrl site = ConfluenceUrl.parse("https://acme.atlassian.net/wiki", false);
        assertThat(site.resolve("/rest/api/content/search?cql=x&cursor=abc"))
                .hasToString("https://acme.atlassian.net/wiki/rest/api/content/search?cql=x&cursor=abc");
        assertThat(site.resolve("/download/attachments/12/a%20b.pdf?version=1")).isNotNull();
        for (String bad : List.of("https://evil.example/rest/api/x", "//evil.example/rest/api/x", "/rest/api/../../admin",
                "/admin/users", "/rest/api/%2e%2e/x", "/rest/api/x\\y")) {
            assertThat(site.resolve(bad)).as(bad).isNull();
        }
        assertThat(site.sameOrigin(URI.create("https://acme.atlassian.net/wiki/rest/api/x"))).isTrue();
        assertThat(site.sameOrigin(URI.create("https://other.atlassian.net/wiki/rest/api/x"))).isFalse();
        assertThat(site.sameOrigin(URI.create("http://acme.atlassian.net/wiki/rest/api/x"))).isFalse();
        assertThat(site.sameOrigin(URI.create("https://acme.atlassian.net/other"))).isFalse();
        assertThat(site.sameOrigin(URI.create("https://api.media.atlassian.com/file/1"))).isFalse();
    }

    @Test
    void hosts_whenParsed_allowExactAndOneLabelWildcards_only() {
        HostAllowlist hosts = HostAllowlist.parse("*.atlassian.net, wiki.example.com");
        assertThat(hosts.allows("acme.atlassian.net")).isTrue();
        assertThat(hosts.allows("ACME.atlassian.net.")).isTrue();
        assertThat(hosts.allows("wiki.example.com")).isTrue();
        assertThat(hosts.allows("atlassian.net")).isFalse();
        assertThat(hosts.allows("evilatlassian.net")).isFalse();
        assertThat(hosts.allows("acme.atlassian.net.evil.com")).isFalse();
        assertThat(hosts.allows("x.wiki.example.com")).isFalse();
        assertThat(hosts.allows("13.1.2.3")).isFalse();
        assertThat(HostAllowlist.parse("").isEmpty()).isTrue();
        for (String bad : List.of("*", "*.net", "1.2.3.4", "*.*.atlassian.net", "a*.atlassian.net", "exa mple.com")) {
            assertThatThrownBy(() -> HostAllowlist.parse(bad)).as(bad).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
