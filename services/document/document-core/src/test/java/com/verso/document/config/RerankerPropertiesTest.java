package com.verso.document.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** ADR-0043: the reranker's settings are checked at startup; off needs no address. */
class RerankerPropertiesTest {

    private static RerankerProperties of(boolean enabled, String url, int candidates, Duration timeout, int maxChars) {
        return new RerankerProperties(enabled, url == null ? null : URI.create(url), candidates, Duration.ofMillis(500),
                timeout, maxChars, 4, 3, Duration.ofMinutes(1));
    }

    @Test
    void off_needsNoAddress() {
        assertThat(of(false, null, 20, Duration.ofSeconds(2), 2000).enabled()).isFalse();
    }

    @Test
    void on_needsAnHttpAddressWithoutCredentials() {
        assertThat(of(true, "http://reranker:80", 20, Duration.ofSeconds(2), 2000).baseUrl()).hasHost("reranker");
        assertThatThrownBy(() -> of(true, null, 20, Duration.ofSeconds(2), 2000)).hasMessageContaining("base-url");
        assertThatThrownBy(() -> of(true, "file:///etc/passwd", 20, Duration.ofSeconds(2), 2000)).hasMessageContaining("base-url");
        assertThatThrownBy(() -> of(true, "http://u:p@reranker:80", 20, Duration.ofSeconds(2), 2000)).hasMessageContaining("credentials");
    }

    @Test
    void limits_areBounded() {
        assertThatThrownBy(() -> of(false, null, 1, Duration.ofSeconds(2), 2000)).hasMessageContaining("candidates");
        assertThatThrownBy(() -> of(false, null, 101, Duration.ofSeconds(2), 2000)).hasMessageContaining("candidates");
        assertThatThrownBy(() -> of(false, null, 20, Duration.ZERO, 2000)).hasMessageContaining("timeout");
        assertThatThrownBy(() -> of(false, null, 20, Duration.ofSeconds(2), 10)).hasMessageContaining("max-passage-chars");
    }
}
