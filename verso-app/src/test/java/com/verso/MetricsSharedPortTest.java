package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Phase 7 review B3: with the actuator on the API port (management.server.port unset), the Prometheus scrape is not
 * open: the token-free exception belongs to the separate management port only.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=")
@VersoTestEnvironment
class MetricsSharedPortTest {

    @Value("${local.server.port}")
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    void prometheus_whenTheActuatorSharesTheApiPort_needsAToken() throws Exception {
        assertThat(get(null).statusCode()).isEqualTo(401);
        assertThat(get(TestIdp.bearer("acct-ops")).statusCode()).as("with a token it answers").isEqualTo(200);
    }

    private HttpResponse<String> get(String bearer) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/prometheus"));
        if (bearer != null) request.header("Authorization", bearer);
        return http.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
