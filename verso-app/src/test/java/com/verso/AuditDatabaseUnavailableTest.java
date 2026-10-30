package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.support.AuditTables;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The audit and work pools are part of readiness (application.yml, ADR-0026, ADR-0057): with the svc_audit or the
 * svc_work login down the container must stop reporting ready, like with the document (DatabaseUnavailableTest) and
 * qa (QaDatabaseUnavailableTest) pools, instead of staying healthy while audit records pile up in the outboxes and
 * every skill run answers 503. Real outage of the login, not a mock: its sessions are terminated and new logins
 * refused; the probe reports its status only, never the role or a message.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.audit.datasource.connection-timeout=1s",
                "verso.work.datasource.connection-timeout=1s"})
@VersoTestEnvironment
class AuditDatabaseUnavailableTest {

    @Value("${local.management.port}")
    int managementPort;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @Test
    void readiness_whenTheAuditOrWorkLoginIsDown_reportsDownAndRecovers() throws Exception {
        assertThat(readiness().statusCode()).isEqualTo(200);
        for (String role : new String[]{"svc_audit", "svc_work"}) {
            AuditTables.update("ALTER ROLE " + role + " NOLOGIN");
            try {
                AuditTables.count("select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity "
                        + "where usename = ?) t", role);

                HttpResponse<String> down = readiness();
                assertThat(down.statusCode()).as(role).isEqualTo(503);
                assertThat(down.body()).contains("\"status\":\"DOWN\"").doesNotContain(role).doesNotContain("password");
            } finally {
                AuditTables.update("ALTER ROLE " + role + " LOGIN");
            }
            // The pool reconnects on its own once logins work again (Hikari retries in the background).
            int status = 0;
            for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
                 status != 200 && System.nanoTime() < deadline; ) {
                status = readiness().statusCode();
            }
            assertThat(status).as("recovers after " + role).isEqualTo(200);
        }
    }

    private HttpResponse<String> readiness() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + managementPort + "/actuator/health/readiness"))
                .timeout(Duration.ofSeconds(30)).GET().build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
