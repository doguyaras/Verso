package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Conversation history and answer feedback end to end (ADR-0023) with the real database: every answer is stored with
 * its citation references; the owner lists, reads, renames, rates and deletes; another account gets 404 for all of it
 * (IDOR, reference 6.5); a deleted document's citation stays and is marked; no question, answer or title in any log.
 * The turn limit is lowered here so the 409 path is cheap.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.qa.history.max-turns-per-conversation=3"})
@VersoTestEnvironment
class ConversationApiTest {

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
    io.micrometer.core.instrument.MeterRegistry meters;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String owner = "acct-hist-a-" + UUID.randomUUID();
    private final String other = "acct-hist-b-" + UUID.randomUUID();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        while (worker.isPaused()) Thread.sleep(50);
        root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Test
    void ask_whenAnswered_storesTheTurnWithItsCitationReferencesAndTimings() throws Exception {
        UUID document = ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yirmi iş günü [1].");

        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin kaç gün?", null);

        assertThat(answer.statusCode()).isEqualTo(200);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        assertThat(conversation).isNotBlank();
        assertThat(turn).isNotBlank();

        HttpResponse<String> read = get(owner, "/v1/conversations/" + conversation);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat((String) JsonPath.read(read.body(), "$.title")).isEqualTo("topic-leave Yıllık izin kaç gün?");
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].id")).isEqualTo(turn);
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].question")).isEqualTo("topic-leave Yıllık izin kaç gün?");
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].answer")).isEqualTo("Yirmi iş günü [1].");
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].outcome")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].mode")).isEqualTo("local");
        assertThat((String) JsonPath.read(read.body(), "$.turns[0].model")).isEqualTo("test-chat");
        assertThat((Integer) JsonPath.read(read.body(), "$.turns[0].durationMs")).isGreaterThanOrEqualTo(0);
        assertThat(read.body()).contains("\"citations\":[{\"number\":1,\"documentId\":\"" + document
                + "\",\"fileName\":\"izin.pdf\",\"page\":1,\"unit\":\"PAGE\",\"documentDeleted\":false,"
                + "\"documentAccessible\":true,\"collectionId\":null,\"collectionName\":null}]")
                .contains("\"feedback\":null").doesNotContain("excerpt");
        // Citation references only: no passage text in the history (ADR-0023).
        assertThat(QaTables.count("select count(*) from qa.turn_citation c join qa.turn t on t.id = c.turn_id "
                + "where t.account_id = ?", owner)).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ? and retrieval_ms >= 0 "
                + "and chat_ms >= 0 and total_ms >= chat_ms", owner)).isOne();
    }

    @Test
    void ask_whenAConversationIsGiven_appendsAndListsItFirst() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        String first = JsonPath.read(ask(owner, "topic-leave İlk soru?", null).body(), "$.conversationId");
        String second = JsonPath.read(ask(owner, "topic-leave Başka konu?", null).body(), "$.conversationId");
        HttpResponse<String> appended = ask(owner, "topic-leave İkinci soru?", UUID.fromString(first));
        assertThat((String) JsonPath.read(appended.body(), "$.conversationId")).isEqualTo(first);

        HttpResponse<String> list = get(owner, "/v1/conversations?page=0&size=10");
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat((List<String>) JsonPath.read(list.body(), "$.data[*].id")).containsExactly(first, second);
        assertThat((List<Integer>) JsonPath.read(list.body(), "$.data[*].turnCount")).containsExactly(2, 1);
        assertThat((Integer) JsonPath.read(list.body(), "$.page.totalElements")).isEqualTo(2);
        assertThat(get(owner, "/v1/conversations?size=51").statusCode()).as("page size cap").isEqualTo(400);
        assertThat(get(owner, "/v1/conversations?size=0").statusCode()).isEqualTo(400);
    }

    @Test
    void ask_whenTheConversationIsFull_answers409BeforeAskingTheModel() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        String conversation = JsonPath.read(ask(owner, "topic-leave 1?", null).body(), "$.conversationId");
        ask(owner, "topic-leave 2?", UUID.fromString(conversation));
        ask(owner, "topic-leave 3?", UUID.fromString(conversation));
        int calls = TestChatModel.INSTANCE.calls();

        HttpResponse<String> full = ask(owner, "topic-leave 4?", UUID.fromString(conversation));

        assertThat(full.statusCode()).isEqualTo(409);
        assertThat(full.body()).contains("\"code\":11022");
        assertThat(TestChatModel.INSTANCE.calls()).isEqualTo(calls);
    }

    /** IDOR (reference 6.5): another account's conversation and turn do not exist for the caller, whatever the verb. */
    @Test
    void conversation_whenAnotherAccountTouchesIt_isNotFoundAndStaysIntact() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        int calls = TestChatModel.INSTANCE.calls();

        assertNotFound(get(other, "/v1/conversations/" + conversation), 11020);
        assertNotFound(send(other, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"x\"}"), 11020);
        assertNotFound(send(other, "DELETE", "/v1/conversations/" + conversation, null), 11020);
        assertNotFound(send(other, "POST", "/v1/conversations/" + conversation + "/turns/" + turn + "/feedback",
                "{\"rating\":\"DOWN\"}"), 11021);
        assertNotFound(ask(other, "topic-leave Yıllık izin?", UUID.fromString(conversation)), 11020);
        assertThat(TestChatModel.INSTANCE.calls()).as("no model call for a foreign conversation").isEqualTo(calls);
        // A turn id under the wrong conversation of its own owner is not found either.
        String another = JsonPath.read(ask(owner, "topic-leave Başka?", null).body(), "$.conversationId");
        assertNotFound(send(owner, "POST", "/v1/conversations/" + another + "/turns/" + turn + "/feedback",
                "{\"rating\":\"UP\"}"), 11021);

        assertThat(send(other, "DELETE", "/v1/conversations", null).statusCode()).isEqualTo(204);
        assertThat((Integer) JsonPath.read(get(other, "/v1/conversations").body(), "$.page.totalElements")).isZero();

        HttpResponse<String> mine = get(owner, "/v1/conversations/" + conversation);
        assertThat(mine.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(mine.body(), "$.title")).isEqualTo("topic-leave Yıllık izin?");
        assertThat((Object) JsonPath.read(mine.body(), "$.turns[0].feedback")).isNull();
        assertThat((Integer) JsonPath.read(get(owner, "/v1/conversations").body(), "$.page.totalElements")).isEqualTo(2);
    }

    @Test
    void rename_whenValid_changesOnlyTheTitleAndRefusesUnsafeText() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String updatedAt = JsonPath.read(get(owner, "/v1/conversations/" + conversation).body(), "$.updatedAt");

        HttpResponse<String> renamed = send(owner, "PATCH", "/v1/conversations/" + conversation,
                "{\"title\":\"  İzin notlarım  \"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(renamed.body(), "$.title")).isEqualTo("İzin notlarım");
        assertThat((String) JsonPath.read(renamed.body(), "$.updatedAt")).as("a rename keeps the order").isEqualTo(updatedAt);

        assertThat(send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\" \"}").statusCode())
                .isEqualTo(400);
        assertThat(send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"a\\u202Eb\"}").statusCode())
                .as("bidi override").isEqualTo(400);
        assertThat(send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"a\\nb\"}").statusCode())
                .as("control character").isEqualTo(400);
        assertThat(send(owner, "PATCH", "/v1/conversations/" + conversation,
                "{\"title\":\"" + "x".repeat(121) + "\"}").statusCode()).isEqualTo(400);
        assertThat(send(owner, "PATCH", "/v1/conversations/not-a-uuid", "{\"title\":\"x\"}").statusCode()).isEqualTo(400);
    }

    @Test
    void delete_whenOneOrAll_removesTurnsRatingsAndActivityButKeepsTheCounts() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        rate(owner, conversation, turn, "{\"rating\":\"UP\"}");
        ask(owner, "topic-leave İkinci?", null);

        assertThat(send(owner, "DELETE", "/v1/conversations/" + conversation, null).statusCode()).isEqualTo(204);
        assertNotFound(get(owner, "/v1/conversations/" + conversation), 11020);
        assertNotFound(send(owner, "DELETE", "/v1/conversations/" + conversation, null), 11020);
        assertThat(QaTables.count("select count(*) from qa.turn_feedback")).as("rating went with its turn").isZero();

        assertThat(send(owner, "DELETE", "/v1/conversations", null).statusCode()).isEqualTo(204);
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", owner)).isZero();
        assertThat(QaTables.count("select count(*) from qa.conversation where account_id = ?", owner)).isZero();
        assertThat(QaTables.count("select count(*) from qa.active_account_day where account_id = ?", owner))
                .as("the activity row is personal data").isZero();
        assertThat(QaTables.count("select coalesce(sum(questions), 0) from qa.usage_daily"))
                .as("anonymous counts stay").isEqualTo(2);
        assertThat(send(owner, "DELETE", "/v1/conversations", null).statusCode()).as("idempotent").isEqualTo(204);
    }

    /** ADR-0023: deleting a document must not break the history; its citation stays, marked as deleted. */
    @Test
    void read_whenACitedDocumentWasDeleted_marksTheCitation() throws Exception {
        UUID document = ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yirmi gün [1].");
        String conversation = JsonPath.read(ask(owner, "topic-leave Yıllık izin?", null).body(), "$.conversationId");

        assertThat(send(owner, "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);

        HttpResponse<String> read = get(owner, "/v1/conversations/" + conversation);
        assertThat(read.statusCode()).isEqualTo(200);
        assertThat(read.body()).contains("\"citations\":[{\"number\":1,\"documentId\":\"" + document
                + "\",\"fileName\":null,\"page\":1,\"unit\":\"PAGE\",\"documentDeleted\":true,"
                + "\"documentAccessible\":false,\"collectionId\":null,\"collectionName\":null}]");
    }

    @Test
    void feedback_whenRatedAgain_replacesTheRatingAndMovesTheDailyCount() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yirmi gün [1].");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");

        HttpResponse<String> up = rate(owner, conversation, turn, "{\"rating\":\"UP\"}");
        assertThat(up.statusCode()).isEqualTo(200);
        assertThat(up.body()).contains("\"rating\":\"UP\"", "\"reason\":null", "\"citationNumber\":null");
        assertThat(rate(owner, conversation, turn, "{\"rating\":\"UP\"}").statusCode()).as("idempotent").isEqualTo(200);
        assertThat(feedbackCount("UP", "NONE")).isOne();

        HttpResponse<String> down = rate(owner, conversation, turn,
                "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":1}");
        assertThat(down.statusCode()).isEqualTo(200);
        assertThat(down.body()).contains("\"rating\":\"DOWN\"", "\"reason\":\"WRONG_SOURCE\"", "\"citationNumber\":1");
        assertThat(feedbackCount("UP", "NONE")).isZero();
        assertThat(feedbackCount("DOWN", "WRONG_SOURCE")).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn_feedback")).as("one row per turn").isOne();

        HttpResponse<String> read = get(owner, "/v1/conversations/" + conversation);
        assertThat(read.body()).contains("\"feedback\":{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":1");
    }

    @Test
    void feedback_whenInconsistent_isRejectedWithoutChangingTheRating() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yirmi gün [1].");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");

        assertThat(rate(owner, conversation, turn, "{\"rating\":\"UP\",\"reason\":\"OTHER\"}").statusCode()).isEqualTo(400);
        assertThat(rate(owner, conversation, turn, "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\"}").statusCode())
                .isEqualTo(400);
        assertThat(rate(owner, conversation, turn, "{\"rating\":\"DOWN\",\"reason\":\"OTHER\",\"citationNumber\":1}")
                .statusCode()).isEqualTo(400);
        assertThat(rate(owner, conversation, turn, "{\"rating\":\"SIDEWAYS\"}").statusCode()).isEqualTo(400);
        assertThat(rate(owner, conversation, turn, "{\"rating\":\"DOWN\",\"comment\":\"free text\"}").statusCode())
                .as("unknown fields are ignored, there is no free text").isEqualTo(200);
        HttpResponse<String> unknownCitation = rate(owner, conversation, turn,
                "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":7}");
        assertThat(unknownCitation.statusCode()).isEqualTo(400);
        assertThat(unknownCitation.body()).contains("\"code\":11023");
        assertThat(feedbackCount("DOWN", "NONE")).isOne();
        assertThat(feedbackCount("DOWN", "WRONG_SOURCE")).isZero();
    }

    @Test
    void history_whenAnonymous_answers401() throws Exception {
        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/conversations"))
                .GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);
    }

    /** llm-rules 2.1: neither the question, the answer nor a title appears in any log of the history paths. */
    @Test
    void history_whenUsed_logsNoQuestionAnswerOrTitle() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("MarkerAnswerText [1].");
        HttpResponse<String> answer = ask(owner, "topic-leave MarkerQuestionSalary?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        get(owner, "/v1/conversations/" + conversation);
        send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"MarkerTitle\"}");
        send(owner, "PATCH", "/v1/conversations/" + conversation, "{\"title\":\"MarkerTitle\\u202E\"}");
        rate(owner, conversation, turn, "{\"rating\":\"DOWN\",\"reason\":\"WRONG_SOURCE\",\"citationNumber\":9}");
        rate(owner, conversation, turn, "{\"rating\":\"DOWN\",\"reason\":\"OUTDATED\"}");
        ask(owner, "topic-leave MarkerQuestionAgain?", UUID.randomUUID());
        send(owner, "DELETE", "/v1/conversations/" + conversation, null);

        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("Conversation renamed"));
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("Answer rated"));
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + event.getMDCPropertyMap()
                    + (event.getArgumentArray() == null ? "" : Stream.of(event.getArgumentArray()).map(String::valueOf)
                    .collect(Collectors.joining(" ")))
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(text).as(event.getLoggerName()).doesNotContain("Marker");
        }
    }

    /**
     * ADR-0023, ADR-0046: the history never enters the answer prompt. A short follow-up goes through the rewrite (the
     * test model repeats it unchanged), and the answer prompt still holds only the question as asked (llm-rules 3.1).
     */
    @Test
    void ask_whenAConversationContinues_doesNotPutTheHistoryIntoThePrompt() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        String conversation = JsonPath.read(ask(owner, "topic-leave MarkerFirstQuestion?", null).body(), "$.conversationId");
        ask(owner, "topic-leave İkinci soru?", UUID.fromString(conversation));

        String prompt = TestChatModel.INSTANCE.lastPrompt().getInstructions().stream()
                .map(m -> m.getText()).collect(Collectors.joining("\n"));
        assertThat(prompt).contains("İkinci soru").doesNotContain("MarkerFirstQuestion").doesNotContain("Yirmi");
    }

    /**
     * ADR-0023: a failed history write loses the turn, never the answer. The caller gets 200 without ids, the failure is
     * counted and logged with its type only, and nothing half-written stays behind.
     */
    @Test
    void ask_whenTheHistoryCannotBeWritten_stillAnswersWithoutIdsAndCountsIt() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("MarkerAnswerLost [1].");
        double before = notStored("failed");
        QaTables.update("REVOKE INSERT ON qa.turn FROM svc_qa");
        HttpResponse<String> answer;
        try {
            answer = ask(owner, "topic-leave MarkerQuestionLost?", null);
        } finally {
            QaTables.update("GRANT INSERT ON qa.turn TO svc_qa");
        }

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).contains("MarkerAnswerLost [1].", "\"conversationId\":null", "\"turnId\":null");
        assertThat(notStored("failed")).isEqualTo(before + 1);
        assertThat(QaTables.count("select count(*) from qa.conversation where account_id = ?", owner))
                .as("rolled back as a whole").isZero();
        assertThat(QaTables.count("select count(*) from qa.active_account_day where account_id = ?", owner)).isZero();
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("Turn not stored: code=HISTORY_WRITE_FAILED"));
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage() + (event.getThrowableProxy() == null ? ""
                    : event.getThrowableProxy().getMessage())).as(event.getLoggerName()).doesNotContain("Marker");
        }
    }

    /** Concurrency (reference 4.3): ratings of one turn racing each other leave one rating and one count. */
    @Test
    void feedback_whenRatedConcurrently_countsOnce() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        String[] bodies = {"{\"rating\":\"UP\"}", "{\"rating\":\"DOWN\",\"reason\":\"OUTDATED\"}",
                "{\"rating\":\"DOWN\"}", "{\"rating\":\"DOWN\",\"reason\":\"OTHER\"}"};
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<HttpResponse<String>>> calls = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                String body = bodies[i % bodies.length];
                calls.add(pool.submit(() -> rate(owner, conversation, turn, body)));
            }
            for (Future<HttpResponse<String>> call : calls) assertThat(call.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(QaTables.count("select count(*) from qa.turn_feedback")).isOne();
        assertThat(QaTables.count("select coalesce(sum(feedback), 0) from qa.usage_daily_feedback")).isOne();
    }

    /**
     * The turn limit holds exactly under concurrency: two answers that both passed the early check race for the last
     * place; one is stored, the other is returned without ids and counted (ConversationServiceImpl, row lock).
     */
    @Test
    void ask_whenTwoAnswersRaceForTheLastTurn_storesExactlyTheLimit() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        UUID conversation = UUID.fromString(JsonPath.read(ask(owner, "topic-leave 1?", null).body(), "$.conversationId"));
        ask(owner, "topic-leave 2?", conversation);
        CountDownLatch bothAsked = new CountDownLatch(2);
        TestChatModel.INSTANCE.answer(prompt -> {
            bothAsked.countDown();
            try {
                bothAsked.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return "Yirmi gün [1].";
        });
        double before = notStored("conversation_full");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<HttpResponse<String>> responses;
        try {
            Future<HttpResponse<String>> a = pool.submit(() -> ask(owner, "topic-leave 3a?", conversation));
            Future<HttpResponse<String>> b = pool.submit(() -> ask(owner, "topic-leave 3b?", conversation));
            responses = List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        assertThat(responses).allMatch(r -> r.statusCode() == 200);
        assertThat(responses).filteredOn(r -> r.body().contains("\"turnId\":null")).hasSize(1);
        assertThat(QaTables.count("select count(*) from qa.turn where conversation_id = ?", conversation)).isEqualTo(3);
        assertThat(notStored("conversation_full")).isEqualTo(before + 1);
    }

    /** A conversation deleted (another tab) while its question was being answered: the turn starts a new one. */
    @Test
    void ask_whenTheConversationIsDeletedMeanwhile_storesTheTurnInANewConversation() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        UUID conversation = UUID.fromString(JsonPath.read(ask(owner, "topic-leave 1?", null).body(), "$.conversationId"));
        TestChatModel.INSTANCE.answer(prompt -> {
            QaTables.update("delete from qa.conversation where id = ?", conversation);
            return "Yirmi gün [1].";
        });

        HttpResponse<String> answer = ask(owner, "topic-leave 2?", conversation);

        assertThat(answer.statusCode()).isEqualTo(200);
        String stored = JsonPath.read(answer.body(), "$.conversationId");
        assertThat(stored).isNotEqualTo(conversation.toString());
        assertThat((String) JsonPath.read(answer.body(), "$.turnId")).isNotNull();
        assertThat(get(owner, "/v1/conversations/" + stored).statusCode()).isEqualTo(200);
    }

    /** A conversation lives retention-days from its start (ADR-0023): a later question opens a new one. */
    @Test
    void ask_whenTheConversationIsOlderThanTheRetention_startsANewOne() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        UUID conversation = UUID.fromString(JsonPath.read(ask(owner, "topic-leave 1?", null).body(), "$.conversationId"));
        QaTables.update("update qa.conversation set created_at = now() - interval '91 days' where id = ?", conversation);

        HttpResponse<String> answer = ask(owner, "topic-leave 2?", conversation);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(answer.body(), "$.conversationId")).isNotEqualTo(conversation.toString());
        assertThat(QaTables.count("select count(*) from qa.turn where conversation_id = ?", conversation)).isOne();
    }

    /**
     * Regression (re-review of ADR-0023): PostgreSQL text refuses U+0000, so a question carrying one (valid JSON) used to
     * fail the history write: the turn was lost, an ERROR was logged and reason="failed" raised the HistoryNotStored
     * outage alarm, at the will of any user. The turn is stored now, with U+FFFD in its place.
     */
    @Test
    void ask_whenTheQuestionHoldsANulCharacter_storesTheTurnAndRaisesNoFailure() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        double failedBefore = notStored("failed");

        HttpResponse<String> answer = send(owner, "POST", "/v1/questions", "{\"question\":\"topic-leave izin\\u0000?\"}");

        assertThat(answer.statusCode()).isEqualTo(200);
        String turn = JsonPath.read(answer.body(), "$.turnId");
        assertThat(turn).isNotNull();
        assertThat(QaTables.count("select count(*) from qa.turn where id = ?::uuid and question = ?", turn,
                "topic-leave izin�?")).isOne();
        assertThat(notStored("failed")).as("no outage counted").isEqualTo(failedBefore);
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("HISTORY_WRITE_FAILED"));
    }

    /**
     * Check-then-act between the ownership read and the rating write (FeedbackServiceImpl): the turn is deleted (its
     * conversation, or the retention) while it is being rated. The rating waits on the deleting transaction, then hits
     * the foreign key: 404 TURN_NOT_FOUND, no rating row and no count, never a 500. Deterministic: the delete commits
     * only once the rating is seen waiting for its lock.
     */
    @Test
    void feedback_whenTheTurnIsDeletedWhileRated_answers404AndCountsNothing() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        HttpResponse<String> answer = ask(owner, "topic-leave Yıllık izin?", null);
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        String turn = JsonPath.read(answer.body(), "$.turnId");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (java.sql.Connection deleting = QaTables.admin();
             java.sql.Statement st = deleting.createStatement()) {
            deleting.setAutoCommit(false);
            st.executeUpdate("delete from qa.turn where id = '" + UUID.fromString(turn) + "'");

            Future<HttpResponse<String>> rating = pool.submit(() -> rate(owner, conversation, turn, "{\"rating\":\"UP\"}"));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (QaTables.count("select count(*) from pg_stat_activity where usename = 'svc_qa' "
                    + "and wait_event_type = 'Lock'") == 0) {
                assertThat(rating.isDone()).as("the rating must wait for the deleting transaction").isFalse();
                assertThat(System.nanoTime()).as("rating never waited for the lock").isLessThan(deadline);
                Thread.onSpinWait();
            }
            deleting.commit();

            HttpResponse<String> response = rating.get(30, TimeUnit.SECONDS);
            assertNotFound(response, 11021);
        } finally {
            pool.shutdownNow();
        }
        assertThat(QaTables.count("select count(*) from qa.turn_feedback")).isZero();
        assertThat(QaTables.count("select coalesce(sum(feedback), 0) from qa.usage_daily_feedback")).isZero();
        assertThat(appender.list).noneMatch(e -> e.getLevel() == ch.qos.logback.classic.Level.ERROR);
    }

    private double notStored(String reason) {
        io.micrometer.core.instrument.Counter counter = meters.find("verso.qa.history.not.stored").tag("reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private long feedbackCount(String rating, String reason) {
        return QaTables.count("select coalesce(sum(feedback), 0) from qa.usage_daily_feedback where rating = ? "
                + "and reason = ?", rating, reason);
    }

    private static void assertNotFound(HttpResponse<String> response, int code) {
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":" + code).doesNotContain("acct-");
    }

    private UUID ready(String account, String text) {
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private HttpResponse<String> ask(String account, String question, UUID conversationId) throws Exception {
        String body = "{\"question\":\"" + question.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (conversationId == null ? "" : ",\"conversationId\":\"" + conversationId + "\"") + "}";
        return send(account, "POST", "/v1/questions", body);
    }

    private HttpResponse<String> rate(String account, String conversation, String turn, String body) throws Exception {
        return send(account, "POST", "/v1/conversations/" + conversation + "/turns/" + turn + "/feedback", body);
    }

    private HttpResponse<String> get(String account, String path) throws Exception {
        return send(account, "GET", path, null);
    }

    private HttpResponse<String> send(String account, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", TestIdp.bearer(account)).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
