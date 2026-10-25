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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Follow-up questions end to end (ADR-0046) with the real database and a stub model: a follow-up is searched with the
 * query the model rewrote from the same conversation, the answer prompt keeps the question as asked, the stream reports
 * the rewrite as its first stage, the rewrite sees only the asker's own conversation, and neither the history nor the
 * rewritten query reaches a log line (llm-rules 2.1).
 *
 * <p>The test embedding gives a text its "topic-x" vector: a follow-up without the topic finds nothing on its own, so an
 * answer with a citation proves retrieval used the rewritten query.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class FollowUpApiTest {

    private static final String REWRITTEN = "topic-leave MarkerRewritten yıllık izin devri kaç gündür?";

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
    private final String owner = "acct-follow-a-" + UUID.randomUUID();
    private final String other = "acct-follow-b-" + UUID.randomUUID();
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
    void ask_whenAFollowUp_searchesTheRewrittenQueryAndAnswersTheQuestionAsAsked() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days; five days carry over.");
        TestChatModel.INSTANCE.answer("Beş gün devreder [1].");
        UUID conversation = conversationOf(ask(owner, "topic-leave MarkerFirstQuestion yıllık izin kaç gün?", null));
        TestChatModel.INSTANCE.rewrite(prompt -> REWRITTEN);

        HttpResponse<String> answer = ask(owner, "Peki bunun devri?", conversation);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat((String) JsonPath.read(answer.body(), "$.contextQuery")).isEqualTo(REWRITTEN);
        assertThat((String) JsonPath.read(answer.body(), "$.outcome")).isEqualTo("ANSWERED");
        assertThat(TestEmbeddingModel.INSTANCE.lastInputs()).containsExactly(REWRITTEN);
        assertThat(TestChatModel.INSTANCE.rewriteCalls()).isEqualTo(1);
        assertThat(text(TestChatModel.INSTANCE.lastRewritePrompt())).contains("MarkerFirstQuestion")
                .contains("Son soru: Peki bunun devri?");
        // The answer prompt: the question as asked; no earlier question, no rewritten query (answer-with-context off).
        assertThat(text(TestChatModel.INSTANCE.lastPrompt())).contains("Soru: Peki bunun devri?")
                .doesNotContain("MarkerFirstQuestion").doesNotContain("MarkerRewritten");
        // The history keeps the question as asked, not the rewrite (ADR-0046: the rewritten query is not stored).
        String stored = get(owner, "/v1/conversations/" + conversation).body();
        assertThat(stored).contains("Peki bunun devri?").doesNotContain("MarkerRewritten");
        assertNoContentLogged();
    }

    @Test
    void ask_whenTheQuestionStandsAlone_asksNoRewrite() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        UUID conversation = conversationOf(ask(owner, "topic-leave Yıllık izin kaç gün?", null));

        HttpResponse<String> answer = ask(owner, "topic-leave Beş yıldan az çalışan kaç gün yıllık izin kullanır?",
                conversation);

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(TestChatModel.INSTANCE.rewriteCalls()).isZero();
        assertThat(answer.body()).contains("\"contextQuery\":null");
    }

    /** No leakage: the rewrite sees only the conversation named, of the asker; another account gets 404 first. */
    @Test
    void ask_whenTheAskerHasOtherConversations_rewritesFromTheNamedOneOnly() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        conversationOf(ask(owner, "topic-leave MarkerConversationA yıllık izin?", null));
        UUID b = conversationOf(ask(owner, "topic-leave MarkerConversationB yıllık izin?", null));

        ask(owner, "Peki bunun?", b);
        HttpResponse<String> foreign = ask(other, "Peki bunun?", b);

        assertThat(text(TestChatModel.INSTANCE.lastRewritePrompt())).contains("MarkerConversationB")
                .doesNotContain("MarkerConversationA");
        assertThat(foreign.statusCode()).isEqualTo(404);
        assertThat(TestChatModel.INSTANCE.rewriteCalls()).isEqualTo(1);
    }

    /** The latest two turns, oldest first (verso.qa.followup.turns): the first of three is no longer sent. */
    @Test
    void ask_whenTheConversationIsLonger_rewritesFromTheLatestTwoTurnsInOrder() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days.");
        UUID conversation = conversationOf(ask(owner, "topic-leave MarkerTurnOne yıllık izin?", null));
        ask(owner, "topic-leave MarkerTurnTwo yıllık izin kaç gün?", conversation);
        ask(owner, "topic-leave MarkerTurnThree izin devri var mı?", conversation);

        ask(owner, "Peki bunun?", conversation);

        String prompt = text(TestChatModel.INSTANCE.lastRewritePrompt());
        assertThat(prompt).doesNotContain("MarkerTurnOne").contains("MarkerTurnTwo").contains("MarkerTurnThree");
        assertThat(prompt.indexOf("MarkerTurnTwo")).isLessThan(prompt.indexOf("MarkerTurnThree"));
    }

    @Test
    void stream_whenAFollowUp_reportsTheRewritingStageFirstAndTheQueryWhenDone() throws Exception {
        ready(owner, "topic-leave Annual leave is twenty working days; five days carry over.");
        TestChatModel.INSTANCE.answer("Beş gün devreder [1].");
        UUID conversation = conversationOf(ask(owner, "topic-leave MarkerFirstQuestion yıllık izin kaç gün?", null));
        TestChatModel.INSTANCE.rewrite(prompt -> REWRITTEN);

        List<String[]> events = parse(stream(owner, "Peki bunun devri?", conversation).body());

        List<String> stages = events.stream().filter(e -> e[0].equals("stage")).map(e -> e[1]).toList();
        assertThat(stages).hasSize(4);
        assertThat(stages.get(0)).contains("\"stage\":\"REWRITING\"");
        assertThat(stages.get(1)).contains("\"stage\":\"RETRIEVING\"");
        assertThat(stages.get(3)).contains("\"stage\":\"WRITING\"");
        String done = events.stream().filter(e -> e[0].equals("done")).findFirst().orElseThrow()[1];
        assertThat((String) JsonPath.read(done, "$.contextQuery")).isEqualTo(REWRITTEN);
        assertThat((String) JsonPath.read(done, "$.outcome")).isEqualTo("ANSWERED");

        // A standalone question in the same conversation: no rewrite stage.
        List<String[]> standalone = parse(stream(owner, "topic-leave Beş yıldan az çalışan kaç gün izin kullanır?",
                conversation).body());
        assertThat(standalone.stream().filter(e -> e[0].equals("stage")).map(e -> e[1]))
                .noneMatch(data -> data.contains("REWRITING"));
        assertNoContentLogged();
    }

    private void assertNoContentLogged() {
        for (ILoggingEvent event : appender.list) {
            String text = event.getFormattedMessage() + " " + event.getMDCPropertyMap()
                    + (event.getArgumentArray() == null ? "" : Stream.of(event.getArgumentArray()).map(String::valueOf)
                    .collect(Collectors.joining(" ")))
                    + (event.getThrowableProxy() == null ? "" : event.getThrowableProxy().getMessage());
            assertThat(text).as(event.getLoggerName()).doesNotContain("Marker").doesNotContain("bunun devri");
        }
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("followUp=rewritten"));
    }

    private static String text(Prompt prompt) {
        return prompt.getInstructions().stream().map(Message::getText).collect(Collectors.joining("\n"));
    }

    private static UUID conversationOf(HttpResponse<String> response) {
        assertThat(response.statusCode()).isEqualTo(200);
        return UUID.fromString(JsonPath.read(response.body(), "$.conversationId"));
    }

    /** Server-sent events as [name, data] pairs ("event:x", "data:y", blank line); comments are skipped. */
    private static List<String[]> parse(String body) {
        List<String[]> events = new ArrayList<>();
        String name = null;
        StringBuilder data = new StringBuilder();
        for (String line : body.split("\n", -1)) {
            if (line.isEmpty()) {
                if (name != null) events.add(new String[] {name, data.toString()});
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

    private void ready(String account, String text) {
        DocumentRow row = documents.insert(account, "izin.pdf", DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(text));
        worker.runOnce();
        assertThat(documents.find(account, row.id()).orElseThrow().status().name()).isEqualTo("READY");
    }

    private static String body(String question, UUID conversationId) {
        return "{\"question\":\"" + question.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
                + (conversationId == null ? "" : ",\"conversationId\":\"" + conversationId + "\"") + "}";
    }

    private HttpResponse<String> ask(String account, String question, UUID conversationId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/questions"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(question, conversationId)))
                .timeout(Duration.ofSeconds(30)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> stream(String account, String question, UUID conversationId) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/questions/stream"))
                .header("Authorization", TestIdp.bearer(account)).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body(question, conversationId)))
                .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String account, String path) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", TestIdp.bearer(account)).GET().timeout(Duration.ofSeconds(30)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private String base() {
        return "http://localhost:" + port;
    }
}
