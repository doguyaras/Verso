package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.verso.document.repository.DocumentRepository;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * A database that is temporarily unavailable answers 503 SERVICE_UNAVAILABLE with Retry-After, not 500 (phase 4
 * reviews C4/R4/D2; repo-context section 3). Covers both families Spring maps such failures to: resource failures
 * (no connection, pool exhausted) and transient ones (lock timeout).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@VersoTestEnvironment
class DatabaseUnavailableTest {

    @Value("${local.server.port}")
    int port;

    @MockitoBean
    DocumentRepository repository;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void request_whenTheDatabaseIsUnreachable_answers503WithRetryAfter() throws Exception {
        when(repository.list(anyString(), anyInt(), anyInt())).thenThrow(new CannotGetJdbcConnectionException("down"));
        assertUnavailable(get("/v1/documents"));
    }

    @Test
    void request_whenALockCannotBeTaken_answers503WithRetryAfter() throws Exception {
        when(repository.list(anyString(), anyInt(), anyInt())).thenThrow(new CannotAcquireLockException("lock timeout"));
        assertUnavailable(get("/v1/documents"));
    }

    private static void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":99997").contains("\"service\":\"system\"")
                .doesNotContain("down").doesNotContain("lock timeout");
        assertThat(response.headers().firstValue("Retry-After")).hasValue("5");
    }

    private HttpResponse<String> get(String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", TestIdp.bearer("acct-db-down")).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
