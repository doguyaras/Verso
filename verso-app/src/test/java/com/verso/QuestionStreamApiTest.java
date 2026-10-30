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
import com.verso.support.AuditTables;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * POST /v1/questions/stream end to end (ADR-0035) on a real port and the real database: the event order, citations
 * validated on the whole text exactly like the JSON path, the uncited and not-found outcomes, a client that leaves
 * cancelling the model stream (the test model counts its words) and storing nothing, the shared bulkhead and time
 * bound, safe error events, plain HTTP errors before the stream opens, and no question or answer text in any log.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class QuestionStreamApiTest {

    private static final String MARKER = "StreamPrivacyMarker";

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

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String account = "acct-stream-" + UUID.randomUUID();
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        QaTables.reset();
        AuditTables.reset();
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
        TestChatModel.INSTANCE.reset();
    }

    /** ADR-0047: the groundedness check runs after the citations are validated and travels in the citations event. */
    @Test
    void stream_whenACitedSentenceIsUnsupported_sendsItInTheCitationsEvent() throws Exception {
        ready("topic-leave Yillik izin 20 is gunudur. Babalik izni 5 is gunudur.");
        String answer = "Yillik izin 20 is gunudur [1]. Babalik izni 7 is gunudur [1].";
        TestChatModel.INSTANCE.answer(answer);

        List<Event> events = parse(stream("topic-leave Babalik izni kac gun?", null).body());

        Event citations = events.get(events.size() - 2);
        assertThat(citations.name()).isEqualTo("citations");
        assertThat(JsonPath.<Integer>read(citations.data(), "$.unverified[0].start")).isEqualTo(answer.indexOf("Babalik"));
        assertThat(JsonPath.<List<Integer>>read(citations.data(), "$.unverified[0].citations")).containsExactly(1);
        assertThat(events.getLast().data()).contains("\"outcome\":\"ANSWERED\"");
    }

    /**
     * ADR-0064: the deltas carried the model's own refusal and its marker; the citations event is the validated
     * answer, here the fixed not-found sentence without citations, and done says NOT_FOUND, as the JSON endpoint does.
     */
    @Test
    void stream_whenTheModelRefusesWithACitation_endsNotFoundWithNoCitation() throws Exception {
        ready("topic-leave Annual leave rules.");
        TestChatModel.INSTANCE.answer("Belgelerde bu konuda bilgi yer almamaktadır [1].");

        List<Event> events = parse(stream("topic-leave Yaz okulunda bir kredilik ders kaç TL?", null).body());

        Event citations = events.get(events.size() - 2);
        assertThat(citations.name()).isEqualTo("citations");
        assertThat((String) JsonPath.read(citations.data(), "$.answer")).isEqualTo("Belgelerde bu sorunun cevabı bulunamadı.");
        assertThat((List<String>) JsonPath.read(citations.data(), "$.citations")).isEmpty();
        assertThat(events.getLast().data()).contains("\"outcome\":\"NOT_FOUND\"", "\"found\":false");
        String turnId = JsonPath.read(events.getLast().data(), "$.turnId");
        assertThat(QaTables.count("select count(*) from qa.turn where id = ?::uuid and outcome = 'NOT_FOUND'", turnId))
                .isOne();
        assertNoContentLogged();
    }

    /** The order of the contract: real stages, the text as it came, validated citations, then done with the ids. */
    @Test
    void stream_whenAPassageMatches_sendsStagesDeltasValidatedCitationsAndDone() throws Exception {
        UUID id = ready("topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yıllık izin yirmi iş günüdür [1]. Ayrıca ek izin vardır [7].");

        HttpResponse<String> response = stream("topic-leave Yıllık izin kaç gün? " + MARKER, null);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("text/event-stream"));
        assertThat(response.headers().firstValue("Cache-Control")).hasValue("no-store, private");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).hasValue("no");
        assertThat(response.headers().firstValue("X-Rag-Mode")).hasValue("local");
        List<Event> events = parse(response.body());
        List<String> names = events.stream().map(Event::name).toList();
        assertThat(names.subList(0, 3)).containsExactly("stage", "stage", "stage");
        assertThat(events.get(0).data()).contains("\"stage\":\"RETRIEVING\"");
        assertThat(events.get(1).data()).contains("\"stage\":\"PASSAGES_FOUND\"", "\"relevant\":1");
        assertThat(events.get(2).data()).contains("\"stage\":\"WRITING\"");
        assertThat(names.subList(3, names.size() - 2)).isNotEmpty().allMatch("delta"::equals);
        assertThat(names.subList(names.size() - 2, names.size())).containsExactly("citations", "done");
        // The deltas are the raw model text, unvalidated; the citations event is the validated answer.
        String streamed = events.stream().filter(e -> e.name().equals("delta"))
                .map(e -> (String) JsonPath.read(e.data(), "$.text")).reduce("", String::concat);
        assertThat(streamed).contains("[7]");
        Event citations = events.get(events.size() - 2);
        assertThat((String) JsonPath.read(citations.data(), "$.answer")).contains("yirmi iş günüdür [1].")
                .doesNotContain("[7]");
        assertThat((List<String>) JsonPath.read(citations.data(), "$.citations[*].documentId"))
                .containsExactly(id.toString());
        Event done = events.getLast();
        assertThat(done.data()).contains("\"outcome\":\"ANSWERED\"", "\"found\":true", "\"mode\":\"local\"");
        String turnId = JsonPath.read(done.data(), "$.turnId");
        String conversationId = JsonPath.read(done.data(), "$.conversationId");
        // The turn is stored like a JSON answer: same outcome, validated text, one citation, and its audit event.
        assertThat(QaTables.count("select count(*) from qa.turn where id = ?::uuid and conversation_id = ?::uuid "
                + "and account_id = ? and outcome = 'ANSWERED'", turnId, conversationId, account)).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn_citation where turn_id = ?::uuid", turnId)).isOne();
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'QUESTION_ASKED' "
                + "and actor_sub = ? and target_id = ?::uuid", account, turnId)).isOne();
        assertNoContentLogged();
    }

    /** A model text without a valid marker ends UNCITED, exactly as the JSON endpoint says it. */
    @Test
    void stream_whenTheModelCitesNothing_endsUncitedWithNoCitation() throws Exception {
        ready("topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.answer("Yirmi gün olabilir [9].");

        List<Event> events = parse(stream("topic-leave Yıllık izin?", null).body());

        Event citations = events.get(events.size() - 2);
        assertThat(citations.name()).isEqualTo("citations");
        assertThat(citations.data()).contains("\"citations\":[]").doesNotContain("[9]");
        assertThat(events.getLast().data()).contains("\"outcome\":\"UNCITED\"", "\"found\":false");
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ? and outcome = 'UNCITED'", account))
                .isOne();
    }

    /** llm-rules 3.4: below the gate the model is not asked; no WRITING stage is reported. */
    @Test
    void stream_whenNothingIsCloseEnough_endsNotFoundWithoutAModelCall() throws Exception {
        ready("topic-weather It rains in spring.");

        List<Event> events = parse(stream("topic-leave Yıllık izin?", null).body());

        assertThat(events.stream().map(Event::name)).containsExactly("stage", "stage", "citations", "done");
        assertThat(events.get(1).data()).contains("\"relevant\":0");
        assertThat(events.stream().map(Event::data)).noneMatch(data -> data.contains("WRITING"));
        assertThat(events.get(2).data()).contains("Belgelerde bu sorunun cevabı bulunamadı.", "\"citations\":[]");
        assertThat(events.get(3).data()).contains("\"outcome\":\"NOT_FOUND\"");
        assertThat(TestChatModel.INSTANCE.calls()).isZero();
    }

    /**
     * ADR-0035: a client that leaves cancels the model stream (no more words are produced), frees the chat slot and
     * stores nothing: no turn, no usage, no audit event.
     */
    @Test
    void stream_whenTheClientLeaves_cancelsTheModelAndStoresNothing() throws Exception {
        ready("topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.streamEvery(Duration.ofMillis(100));
        TestChatModel.INSTANCE.answer("kelime ".repeat(60) + "[1].");

        HttpResponse<InputStream> response = http.send(request("topic-leave Yıllık izin?", null),
                HttpResponse.BodyHandlers.ofInputStream());
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && !line.equals("event:delta")) {
                // up to the first word
            }
            assertThat(line).isEqualTo("event:delta");
        }

        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (!TestChatModel.INSTANCE.streamCancelled()) {
            assertThat(System.nanoTime()).as("the model stream is cancelled").isLessThan(deadline);
            Thread.sleep(50);
        }
        int produced = TestChatModel.INSTANCE.emittedTokens();
        Thread.sleep(500);
        assertThat(TestChatModel.INSTANCE.emittedTokens()).as("no word after the cancel").isEqualTo(produced)
                .isLessThan(61);
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", account)).isZero();
        assertThat(QaTables.count("select count(*) from qa.conversation where account_id = ?", account)).isZero();
        assertThat(AuditTables.count("select count(*) from audit.event where actor_sub = ?", account)).isZero();
        // Both chat slots are free again: a JSON question is answered at once.
        TestChatModel.INSTANCE.reset();
        HttpResponse<String> next = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"topic-leave izin?\"}"))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(next.statusCode()).isEqualTo(200);
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("Question stream cancelled:"));
    }

    /** ADR-0008: the chat bound holds for a stream too (8 s in tests); the model stream is cancelled, a safe error ends it. */
    @Test
    void stream_whenTheModelIsTooSlow_endsWithModelUnavailableAndCancelsIt() throws Exception {
        ready("topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.streamEvery(Duration.ofSeconds(1));
        TestChatModel.INSTANCE.answer("kelime ".repeat(30) + "[1].");

        List<Event> events = parse(stream("topic-leave Yıllık izin?", null).body());

        Event error = events.getLast();
        assertThat(error.name()).isEqualTo("error");
        assertThat(error.data()).contains("\"code\":11001", "\"retryAfter\":5");
        assertThat(events.stream().map(Event::name)).doesNotContain("citations", "done");
        assertThat(TestChatModel.INSTANCE.streamCancelled()).isTrue();
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", account)).isZero();
    }

    /** llm-rules 2.3: a provider failure is a fixed code; its text reaches neither the client nor the log. */
    @Test
    void stream_whenTheModelFails_endsWithASafeErrorEvent() throws Exception {
        ready("topic-leave Annual leave is twenty working days.");
        TestChatModel.INSTANCE.failWith(new IllegalStateException("MarkerProviderEcho of the prompt"));

        HttpResponse<String> response = stream("topic-leave Yıllık izin?", null);

        assertThat(response.statusCode()).isEqualTo(200);
        Event error = parse(response.body()).getLast();
        assertThat(error.name()).isEqualTo("error");
        assertThat(error.data()).contains("\"code\":11001").doesNotContain("MarkerProviderEcho");
        assertThat(QaTables.count("select count(*) from qa.turn where account_id = ?", account)).isZero();
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("Question stream failed: code=MODEL_UNAVAILABLE"));
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("MarkerProviderEcho");
        }
    }

    /** ADR-0008: one bulkhead for both endpoints; with both slots held by JSON questions a stream ends MODEL_BUSY. */
    @Test
    void stream_whenBothChatSlotsAreTaken_endsWithModelBusy() throws Exception {
        ready("topic-leave Annual leave rules.");
        CountDownLatch release = new CountDownLatch(1);
        TestChatModel.INSTANCE.answer(prompt -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return TestChatModel.DEFAULT_ANSWER;
        });
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<HttpResponse<String>>> busy = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                busy.add(pool.submit(() -> http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                                + "/v1/questions")).header("Authorization", TestIdp.bearer(account))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"topic-leave izin?\"}"))
                        .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString())));
            }
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (TestChatModel.INSTANCE.calls() < 2) {
                assertThat(System.nanoTime()).as("both chat calls started").isLessThan(deadline);
                Thread.sleep(20);
            }

            List<Event> events = parse(stream("topic-leave izin?", null).body());

            assertThat(events.getLast().name()).isEqualTo("error");
            assertThat(events.getLast().data()).contains("\"code\":11002", "\"retryAfter\":5");
            assertThat(events.stream().map(Event::data)).noneMatch(data -> data.contains("WRITING"));
            assertThat(TestChatModel.INSTANCE.calls()).isEqualTo(2);
            release.countDown();
            for (Future<HttpResponse<String>> answer : busy) assertThat(answer.get().statusCode()).isEqualTo(200);
        }
    }

    /** Refusals before any model call stay plain HTTP errors in the envelope (ADR-0023 IDOR: another id is 404). */
    @Test
    void stream_whenRefusedBeforeTheModel_answersAPlainErrorEnvelope() throws Exception {
        HttpResponse<String> foreign = stream("topic-leave izin?", UUID.randomUUID());
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(foreign.headers().firstValue("Content-Type")).hasValueSatisfying(
                type -> assertThat(type).startsWith("application/json"));
        assertThat(foreign.body()).contains("\"code\":11020");

        HttpResponse<String> blank = stream(" ", null);
        assertThat(blank.statusCode()).isEqualTo(400);
        assertThat(blank.body()).contains("\"code\":90000");

        HttpResponse<String> anonymous = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/questions/stream")).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"izin?\"}")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(anonymous.statusCode()).isEqualTo(401);

        // The body bound of the JSON endpoint holds here too: a chunked body (no Content-Length) is refused, unread.
        HttpResponse<String> chunked = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port
                        + "/v1/questions/stream")).header("Authorization", TestIdp.bearer(account))
                .header("Content-Type", "application/json").header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(() -> new java.io.ByteArrayInputStream(
                        "{\"question\":\"izin?\"}".getBytes(StandardCharsets.UTF_8)))).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(chunked.statusCode()).isEqualTo(411);
        assertThat(chunked.body()).contains("\"code\":11011");
        assertThat(TestChatModel.INSTANCE.calls()).isZero();
    }

    /** An open circuit refuses at once with 503 and Retry-After, before the stream opens and before any embedding. */
    @Test
    void stream_whenTheCircuitIsOpen_answers503BeforeTheStream() throws Exception {
        for (int i = 0; i < 5; i++) circuit.recordFailure();

        HttpResponse<String> response = stream("topic-leave izin?", null);

        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).isPresent();
        assertThat(response.body()).contains("\"code\":11001");
        assertThat(TestEmbeddingModel.INSTANCE.calls()).isZero();
    }

    private void assertNoContentLogged() {
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + event.getMDCPropertyMap()
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(text).as(event.getLoggerName()).doesNotContain(MARKER, "Yıllık izin", "twenty working days",
                    "yirmi iş");
        }
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().startsWith("Question streamed:"));
    }

    private record Event(String name, String data) {
    }

    /** Server-sent events as Spring writes them ("event:x", "data:y", blank line); comments are skipped. */
    private static List<Event> parse(String body) {
        List<Event> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) {
                if (name != null) events.add(new Event(name, data.toString()));
                name = null;
                data.setLength(0);
            } else if (line.startsWith("event:")) {
                name = line.substring(6);
            } else if (line.startsWith("data:")) {
                if (!data.isEmpty()) data.append('\n');
                data.append(line.substring(5));
            }
        }
        return events;
    }

    private UUID ready(String text) {
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private HttpRequest request(String question, UUID conversationId) {
        String body = "{\"question\":\"" + question.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (conversationId == null ? "" : ",\"conversationId\":\"" + conversationId + "\"") + "}";
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/questions/stream"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).timeout(Duration.ofSeconds(60)).build();
    }

    private HttpResponse<String> stream(String question, UUID conversationId) throws Exception {
        return http.send(request(question, conversationId), HttpResponse.BodyHandlers.ofString());
    }
}
