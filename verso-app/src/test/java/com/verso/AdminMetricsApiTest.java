package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
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
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * GET /v1/admin/metrics (ADR-0023): verso-admin only (ADR-0019), aggregates from the daily rollups, and no question
 * text at all while the installation's policy is off (the default). The k-anonymous list itself:
 * {@link UnansweredQuestionsTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class AdminMetricsApiTest {

    static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    @Value("${local.server.port}")
    int port;

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ModelCircuitBreaker circuit;

    @Autowired
    com.verso.document.api.DocumentCatalog catalog;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String alice = "acct-metrics-a-" + UUID.randomUUID();
    private final String bob = "acct-metrics-b-" + UUID.randomUUID();
    private final String admin = TestIdp.IDP.token().subject("acct-metrics-admin").realmRoles("verso-admin").build();

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    /** ADR-0019: a signed-in user without verso-admin gets 403 ACCESS_DENIED, an anonymous caller 401. */
    @Test
    void metrics_whenCallerIsNotAnAdmin_isDenied() throws Exception {
        HttpResponse<String> user = metrics("Bearer " + TestIdp.IDP.tokenFor(alice), today(), today());
        assertThat(user.statusCode()).isEqualTo(403);
        assertThat(user.body()).contains("\"code\":90101").doesNotContain("questions");
        String operator = TestIdp.IDP.token().subject("acct-operator").realmRoles("verso-operator", "verso-user").build();
        assertThat(metrics("Bearer " + operator, today(), today()).statusCode()).as("verso-operator is no admin")
                .isEqualTo(403);
        assertThat(metrics(null, today(), today()).statusCode()).isEqualTo(401);
    }

    @Test
    void metrics_whenAnswersWereStored_countsOutcomesUsersLatencyModesFeedbackAndDocuments() throws Exception {
        UUID cited = ready(alice, "leave.pdf", "topic-leave Annual leave is twenty working days.");
        UUID neverCited = ready(alice, "travel.pdf", "topic-travel Travel costs are refunded monthly.");
        TestChatModel.INSTANCE.answer("Yirmi gün [1].");
        HttpResponse<String> first = ask(alice, "topic-leave Yıllık izin?");
        ask(alice, "topic-leave Yıllık izin kaç gün?");
        TestChatModel.INSTANCE.answer("Bence yirmi gündür.");
        ask(alice, "topic-leave İzin süresi?");
        ask(bob, "topic-mars How many moons?");
        String conversation = JsonPath.read(first.body(), "$.conversationId");
        String turn = JsonPath.read(first.body(), "$.turnId");
        rate(alice, conversation, turn, "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":1}");

        HttpResponse<String> response = metrics("Bearer " + admin, today().minusDays(2), today());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        String body = response.body();
        assertThat((String) JsonPath.read(body, "$.timeZone")).isEqualTo("Europe/Istanbul");
        assertThat((Integer) JsonPath.read(body, "$.totals.questions")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.totals.activeUsers")).isEqualTo(2);
        assertThat((Boolean) JsonPath.read(body, "$.totals.activeUsersComplete")).isTrue();
        assertThat((Integer) JsonPath.read(body, "$.totals.outcomes.answered")).isEqualTo(2);
        assertThat((Integer) JsonPath.read(body, "$.totals.outcomes.uncited")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(body, "$.totals.outcomes.notFound")).isEqualTo(1);
        assertThat((Double) JsonPath.read(body, "$.totals.outcomeRates.answered")).isEqualTo(0.5);
        assertThat((Integer) JsonPath.read(body, "$.totals.modes.local")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.totals.modes.cloud")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.totals.latency.p50Ms")).isPositive();
        assertThat((Integer) JsonPath.read(body, "$.totals.latency.p95Ms")).isPositive();
        assertThat((Integer) JsonPath.read(body, "$.totals.feedback.down")).isOne();
        assertThat((Integer) JsonPath.read(body, "$.totals.feedback.up")).isZero();
        assertThat((Integer) JsonPath.read(body, "$.totals.feedback.downReasons.WRONG_SOURCE")).isOne();
        assertThat((List<String>) JsonPath.read(body, "$.daily[*].day")).containsExactly(
                today().minusDays(2).toString(), today().minusDays(1).toString(), today().toString());
        assertThat((Integer) JsonPath.read(body, "$.daily[2].questions")).isEqualTo(4);
        assertThat((Integer) JsonPath.read(body, "$.daily[0].questions")).isZero();
        assertThat((List<String>) JsonPath.read(body, "$.topCitedDocuments[*].documentId")).containsExactly(cited.toString());
        assertThat((List<Integer>) JsonPath.read(body, "$.topCitedDocuments[*].citations")).containsExactly(2);
        // Policy off (default): documents are known by id; no file name that could tell what one employee looked up.
        assertThat((Boolean) JsonPath.read(body, "$.documentNamesShown")).isFalse();
        assertThat((List<String>) JsonPath.read(body, "$.topCitedDocuments[*].fileName")).containsExactly((String) null);
        assertThat((List<Boolean>) JsonPath.read(body, "$.topCitedDocuments[*].documentDeleted")).containsExactly(false);
        assertThat((Integer) JsonPath.read(body, "$.neverCitedDocuments.total")).isOne();
        assertThat((Boolean) JsonPath.read(body, "$.neverCitedDocuments.complete")).isTrue();
        assertThat((List<String>) JsonPath.read(body, "$.neverCitedDocuments.documents[*].fileName")).containsExactly((String) null);
        assertThat((List<String>) JsonPath.read(body, "$.neverCitedDocuments.documents[*].documentId"))
                .containsExactly(neverCited.toString());
        // Policy off (default): no question text, not even of questions many users asked.
        assertThat((Map<String, Object>) JsonPath.read(body, "$.unansweredQuestions"))
                .containsEntry("enabled", false).containsEntry("minDistinctUsers", 3);
        assertThat((List<Object>) JsonPath.read(body, "$.unansweredQuestions.questions")).isEmpty();
        // Aggregates only: no account, no question, no answer.
        assertThat(body).doesNotContain("acct-", "topic-", "Yirmi", "Bence", "leave.pdf", "travel.pdf");
    }

    /** The rollups outlive the history: deleting every conversation does not rewrite the past (ADR-0023). */
    @Test
    void metrics_whenTheHistoryWasDeleted_keepTheCountsButNotTheDistinctUsers() throws Exception {
        ready(alice, "leave.pdf", "topic-leave Annual leave is twenty working days.");
        ask(alice, "topic-leave Yıllık izin?");
        assertThat(send(alice, "DELETE", "/v1/conversations").statusCode()).isEqualTo(204);

        String body = metrics("Bearer " + admin, today(), today()).body();
        assertThat((Integer) JsonPath.read(body, "$.totals.questions")).isOne();
        assertThat((Integer) JsonPath.read(body, "$.daily[0].activeUsers")).as("the day's count stays").isOne();
        assertThat((Integer) JsonPath.read(body, "$.totals.activeUsers")).as("the account row is gone").isZero();
    }

    @Test
    void metrics_whenTheRangeIsInvalid_answers400() throws Exception {
        HttpResponse<String> reversed = metrics("Bearer " + admin, today(), today().minusDays(1));
        assertThat(reversed.statusCode()).isEqualTo(400);
        assertThat(reversed.body()).contains("\"code\":11030");
        assertThat(metrics("Bearer " + admin, today().minusDays(366), today()).statusCode()).as("367 days").isEqualTo(400);
        assertThat(metrics("Bearer " + admin, today().minusDays(365), today()).statusCode()).as("366 days").isEqualTo(200);
        assertThat(send("Bearer " + admin, "/v1/admin/metrics?from=2026-10-01").statusCode()).isEqualTo(400);
        assertThat(send("Bearer " + admin, "/v1/admin/metrics?from=yesterday&to=2026-10-01").statusCode()).isEqualTo(400);
    }

    /**
     * Defence in depth (ADR-0023): the account-wide catalog methods refuse every caller without verso-admin, whichever
     * module calls them, not only through the admin endpoint; the owner's lookup stays open and filtered.
     */
    @Test
    void documentCatalog_whenCalledAcrossAccountsWithoutAdmin_isRefused() {
        UUID id = ready(alice, "leave.pdf", "topic-leave Annual leave is twenty working days.");
        try {
            assertThatThrownBy(() -> catalog.describeAcrossAccounts(List.of(id))).as("no authentication")
                    .isInstanceOfAny(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                            org.springframework.security.access.AccessDeniedException.class);
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.TestingAuthenticationToken("u", null, "ROLE_verso-user"));
            assertThatThrownBy(() -> catalog.describeAcrossAccounts(List.of(id)))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThatThrownBy(() -> catalog.readyDocumentsExcept(List.of(), 10))
                    .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
            assertThat(catalog.fileNames(alice, List.of(id))).containsEntry(id, "leave.pdf");
            assertThat(catalog.fileNames(bob, List.of(id))).as("another account's document").isEmpty();
            org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                    new org.springframework.security.authentication.TestingAuthenticationToken("a", null, "ROLE_verso-admin"));
            assertThat(catalog.describeAcrossAccounts(List.of(id))).extracting(d -> d.fileName()).containsExactly("leave.pdf");
        } finally {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
        }
    }

    static LocalDate today() {
        return LocalDate.now(ZONE);
    }

    private UUID ready(String account, String name, String text) {
        DocumentRow row = documents.insert(account, name, DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        return row.id();
    }

    private HttpResponse<String> ask(String account, String question) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void rate(String account, String conversation, String turn, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/conversations/" + conversation + "/turns/" + turn + "/feedback"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(200);
    }

    private HttpResponse<String> metrics(String authorization, LocalDate from, LocalDate to) throws Exception {
        return send(authorization, "/v1/admin/metrics?from=" + from + "&to=" + to);
    }

    private HttpResponse<String> send(String account, String method, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", TestIdp.bearer(account)).method(method, HttpRequest.BodyPublishers.noBody())
                .build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> send(String authorization, String path) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).GET();
        if (authorization != null) request.header("Authorization", authorization);
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }
}
