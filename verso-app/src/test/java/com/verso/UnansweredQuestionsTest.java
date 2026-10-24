package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The unanswered-questions list with the installation's policy turned on (ADR-0023): a "not found" question is listed
 * only when at least k = 3 distinct accounts asked it (k-anonymity), in its normalised form, never with an account.
 * One account asking the same question many times does not count as many users. Document names are turned on here
 * too. Nothing of it reaches a log (llm-rules 2.1).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.qa.metrics.show-unanswered-questions=true",
                "verso.qa.metrics.show-document-names=true"})
@VersoTestEnvironment
class UnansweredQuestionsTest {

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    com.verso.document.repository.DocumentRepository documents;

    @Autowired
    com.verso.document.worker.IngestionWorker worker;

    private ch.qos.logback.classic.Logger root;
    private ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String run = UUID.randomUUID().toString().substring(0, 8);
    private final String admin = TestIdp.IDP.token().subject("acct-k-admin").realmRoles("verso-admin").build();

    @BeforeEach
    void setUp() {
        // No documents at all: every question below is "not found" without a model call.
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        root = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(ch.qos.logback.classic.Logger.ROOT_LOGGER_NAME);
        appender = new ch.qos.logback.core.read.ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void unanswered_whenThreeAccountsAskTheSame_isListedNormalisedAndSmallerGroupsAreNot() throws Exception {
        // Three accounts, spelled differently (case, spaces, punctuation): one group of 3.
        ask("acct-k1-" + run, "Kargo   ücreti ne kadar?");
        ask("acct-k2-" + run, "kargo ücreti ne kadar");
        ask("acct-k3-" + run, "KARGO ÜCRETİ NE KADAR ?");
        // Two accounts only, one of them five times: below k, never shown.
        for (int i = 0; i < 5; i++) ask("acct-k1-" + run, "Maaşım ne zaman yatar?");
        ask("acct-k2-" + run, "Maaşım ne zaman yatar?");

        String body = metrics();

        assertThat((Boolean) JsonPath.read(body, "$.unansweredQuestions.enabled")).isTrue();
        assertThat((Integer) JsonPath.read(body, "$.unansweredQuestions.minDistinctUsers")).isEqualTo(3);
        assertThat((List<String>) JsonPath.read(body, "$.unansweredQuestions.questions[*].question"))
                .containsExactly("kargo ücreti ne kadar");
        assertThat((List<Integer>) JsonPath.read(body, "$.unansweredQuestions.questions[*].distinctUsers"))
                .containsExactly(3);
        assertThat((List<Integer>) JsonPath.read(body, "$.unansweredQuestions.questions[*].asked")).containsExactly(3);
        assertThat(body).doesNotContain("Maaş", "maaş", "acct-k");
        for (var event : appender.list) {
            assertThat(event.getFormattedMessage()).as(event.getLoggerName()).doesNotContainIgnoringCase("kargo")
                    .doesNotContainIgnoringCase("maaş");
        }
    }

    /** With the installation's name policy on, the top and never cited documents carry their file names. */
    @Test
    void documents_whenNamesAreShown_carryTheirFileNames() throws Exception {
        var row = documents.insert("acct-k1-" + run, "izin.pdf", com.verso.document.api.enums.DocumentFormat.PDF, 10, null,
                java.time.Instant.now());
        documents.insertFile(row.id(), com.verso.document.testing.TestPdfs.pages("topic-leave Annual leave rules."));
        worker.runOnce();

        String body = metrics();

        assertThat((Boolean) JsonPath.read(body, "$.documentNamesShown")).isTrue();
        assertThat((List<String>) JsonPath.read(body, "$.neverCitedDocuments.documents[*].fileName")).containsExactly("izin.pdf");
    }

    /** A deleted conversation leaves the k-anonymous count: the list reads the retained history only. */
    @Test
    void unanswered_whenOneAskerDeletesTheirHistory_dropsBelowK() throws Exception {
        ask("acct-k1-" + run, "Otopark kartı nereden alınır?");
        ask("acct-k2-" + run, "Otopark kartı nereden alınır?");
        ask("acct-k3-" + run, "Otopark kartı nereden alınır?");
        assertThat((List<String>) JsonPath.read(metrics(), "$.unansweredQuestions.questions[*].question")).hasSize(1);

        HttpResponse<String> deleted = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/conversations"))
                .header("Authorization", TestIdp.bearer("acct-k3-" + run)).DELETE().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(deleted.statusCode()).isEqualTo(204);

        assertThat((List<String>) JsonPath.read(metrics(), "$.unansweredQuestions.questions")).isEmpty();
    }

    private String metrics() throws Exception {
        LocalDate today = AdminMetricsApiTest.today();
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/admin/metrics?from="
                        + today.minusDays(1) + "&to=" + today)).header("Authorization", "Bearer " + admin).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        return response.body();
    }

    private void ask(String account, String question) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"outcome\":\"NOT_FOUND\"");
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
