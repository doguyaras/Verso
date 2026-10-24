package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The qa pool cannot reach the database (ADR-0023): the history endpoints answer 503 SERVICE_UNAVAILABLE with
 * Retry-After, like the document module (DatabaseUnavailableTest), and a question is still answered, without ids.
 * Real outage of the svc_qa login, not a mock: its sessions are terminated and new logins refused.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.qa.datasource.connection-timeout=1s"})
@VersoTestEnvironment
class QaDatabaseUnavailableTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String account = "acct-qa-down-" + UUID.randomUUID();

    @Test
    void history_whenTheQaLoginIsDown_answers503AndQuestionsStillWork() throws Exception {
        jdbc.update("DELETE FROM document.document");
        TestChatModel.INSTANCE.reset();
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages("topic-leave Annual leave is twenty working days."));
        worker.runOnce();
        QaTables.update("ALTER ROLE svc_qa NOLOGIN");
        try {
            QaTables.count("select count(*) from (select pg_terminate_backend(pid) from pg_stat_activity "
                    + "where usename = 'svc_qa') t");

            assertUnavailable(send("GET", "/v1/conversations", null));
            assertUnavailable(send("DELETE", "/v1/conversations/" + UUID.randomUUID(), null));
            assertUnavailable(send("POST", "/v1/conversations/" + UUID.randomUUID() + "/turns/" + UUID.randomUUID()
                    + "/feedback", "{\"rating\":\"UP\"}"));

            HttpResponse<String> answer = send("POST", "/v1/questions", "{\"question\":\"topic-leave Yıllık izin?\"}");
            assertThat(answer.statusCode()).isEqualTo(200);
            assertThat(answer.body()).contains("\"conversationId\":null", "\"turnId\":null");
        } finally {
            QaTables.update("ALTER ROLE svc_qa LOGIN");
        }
        // The pool reconnects on its own once logins work again (Hikari retries in the background).
        int status = 0;
        for (long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
             status != 200 && System.nanoTime() < deadline; ) {
            status = send("GET", "/v1/conversations", null).statusCode();
        }
        assertThat(status).as("recovers").isEqualTo(200);
    }

    private static void assertUnavailable(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"code\":99997").doesNotContain("svc_qa").doesNotContain("password");
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", TestIdp.bearer(account)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
