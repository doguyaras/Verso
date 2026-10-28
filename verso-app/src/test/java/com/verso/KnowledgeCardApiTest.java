package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.DocumentIngestion;
import com.verso.document.api.dto.IngestionRequest;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.KnowledgeEval;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
import com.verso.support.QaTables;
import com.verso.support.TestChatModel;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.TestIdp;
import com.verso.support.VersoTestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
 * The answer memory end to end (ADR-0049) with the real database, the real outbox and stub models: a verified answer
 * card is served only to callers who can read every document it cites (the collection matrix), never after a cited
 * document was deleted, replaced or moved, after its expiry or past the dispute threshold; the golden-set check
 * records recall and drift and raises its alert; every endpoint is verso-admin only; no question or answer text
 * reaches a log line.
 *
 * <p>Test embedding: equal texts embed equally (similarity 1); texts of one "topic-x" are about 0.9 similar, below the
 * 0.95 match threshold, so only the same question matches a card.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class KnowledgeCardApiTest {

    private static final String QUESTION = "topic-leave MarkerKnowledgeQ yıllık izin kaç gündür?";
    /**
     * The stub model's answer. No number, number word or code, so the groundedness check (ADR-0047) has nothing to
     * compare and the turn is ANSWERED whatever the test PDF says; an UNCITED turn could not be verified (11042).
     */
    private static final String ANSWER = "MarkerKnowledgeA yıllık izin süresi bu belgelerde tanımlıdır [1][2].";
    private static final String ANSWER_ONE = "MarkerKnowledgeA yıllık izin süresi bu belgede tanımlıdır [1].";

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    ModelCircuitBreaker circuit;

    @Autowired
    KnowledgeEval eval;

    @Autowired
    DocumentIngestion ingestion;

    @Autowired
    MeterRegistry registry;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-know-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String secondAdminSub = "acct-know-admin2-" + suffix;
    private final String secondAdmin = TestIdp.IDP.token().subject(secondAdminSub).realmRoles("verso-admin").build();
    /** Reads both collections (both groups). */
    private final String dave = token("acct-know-dave-" + suffix, "/hukuk", "/ik");
    /** Reads only the first collection. */
    private final String alice = token("acct-know-alice-" + suffix, "/hukuk");
    /** No group at all. */
    private final String carol = token("acct-know-carol-" + suffix);
    private Logger root;
    private ListAppender<ILoggingEvent> appender;
    private UUID hukuk;
    private UUID ik;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (worker.isPaused()) {
            assertThat(System.nanoTime()).as("ingestion worker stays paused").isLessThan(deadline);
            Thread.sleep(50);
        }
        hukuk = collection("hukuk " + suffix);
        ik = collection("ik " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        grant(ik, "GROUP", "/ik");
        // The admin reads both (an admin manages collections but reads only with a grant, ADR-0028).
        grant(hukuk, "USER", adminSub);
        grant(ik, "USER", adminSub);
        grant(hukuk, "USER", secondAdminSub);
        grant(ik, "USER", secondAdminSub);
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

    // ---------------------------------------------------------------- access matrix

    @Test
    void card_isServedOnlyToCallersWhoReadEveryCitedDocument() throws Exception {
        UUID first = uploadInto(hukuk, "izin-hukuk.pdf", "topic-leave Hukuk: yillik izin on dort gundur.");
        UUID second = uploadInto(ik, "izin-ik.pdf", "topic-leave IK: yillik izin on dort gundur, devreder.");
        process();
        UUID card = verifiedCard(Map.of(first, true, second, true));
        int chatCalls = TestChatModel.INSTANCE.calls();

        HttpResponse<String> served = ask(dave, QUESTION);
        assertThat(served.statusCode()).as(served.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(served.body(), "$.memory.cardId")).isEqualTo(card.toString());
        assertThat((String) JsonPath.read(served.body(), "$.model")).isEqualTo("verified-answer");
        assertThat((String) JsonPath.read(served.body(), "$.outcome")).isEqualTo("ANSWERED");
        assertThat((String) JsonPath.read(served.body(), "$.answer")).contains("MarkerKnowledgeA");
        assertThat((List<String>) JsonPath.read(served.body(), "$.citations[*].documentId"))
                .containsExactlyInAnyOrder(first.toString(), second.toString());
        assertThat(TestChatModel.INSTANCE.calls()).as("no model call for a verified answer").isEqualTo(chatCalls);
        // The served turn is stored and linked to the card (a down rating is a dispute of it).
        assertThat(QaTables.count("select count(*) from qa.turn_knowledge_card where card_id = ?", card)).isEqualTo(1);

        // One of the two collections only, or none: the normal path, as if the card did not exist.
        TestChatModel.INSTANCE.answer("Normal yol [1].");
        for (String token : List.of(alice, carol)) {
            HttpResponse<String> other = ask(token, QUESTION);
            assertThat(other.statusCode()).as(other.body()).isEqualTo(200);
            assertThat((Object) JsonPath.read(other.body(), "$.memory")).isNull();
            assertThat(other.body()).doesNotContain("MarkerKnowledgeA");
        }
        // Another question of the same topic is below the threshold: no card.
        assertThat((Object) JsonPath.read(ask(dave, "topic-leave Doğum izni kaç hafta?").body(), "$.memory")).isNull();

        // The stream: the "Onaylı bilgi bulundu" stage, the verified text in the citations event, the card in done.
        HttpResponse<String> stream = stream(dave, QUESTION);
        assertThat(stream.statusCode()).isEqualTo(200);
        assertThat(stream.body()).contains("\"stage\":\"MEMORY_FOUND\"").contains("MarkerKnowledgeA")
                .contains("\"cardId\":\"" + card + "\"").doesNotContain("\"stage\":\"WRITING\"");
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- staleness

    @Test
    void deletingACitedDocument_makesTheCardStale_andItIsNotServed() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(Map.of(document, true));
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory.cardId")).isEqualTo(card.toString());

        assertThat(send(admin, "DELETE", "/v1/documents/" + document, null).statusCode()).isEqualTo(204);

        awaitState(card, "STALE");
        HttpResponse<String> detail = get(admin, "/v1/admin/knowledge-cards/" + card);
        assertThat((String) JsonPath.read(detail.body(), "$.staleReason")).isEqualTo("SOURCE_DELETED");
        assertThat((List<String>) JsonPath.read(detail.body(), "$.events[*].reason")).contains("SOURCE_DELETED");
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory")).isNull();
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'KNOWLEDGE_CARD_STALE' "
                + "and actor_sub = 'system:knowledge-memory' and detail_code = 'SOURCE_DELETED'")).isEqualTo(1);
        // A stale card whose source changed cannot be verified again: a new answer makes a new card.
        assertError(send(admin, "POST", "/v1/admin/knowledge-cards/" + card + "/verify", null), 409, 11041);
    }

    @Test
    void replacingOrMovingACitedDocument_makesTheCardStale() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(Map.of(document, true));
        // A connector's new version of the file (ADR-0038): the old document goes in the same transaction.
        ingestion.submit(new IngestionRequest(IngestionRequest.accountOf(UUID.randomUUID()), hukuk, "izin.pdf",
                TestPdfs.pages("topic-leave Yillik izin yirmi gundur."), UUID.randomUUID(), document));
        awaitState(card, "STALE");
        assertThat((String) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + card).body(), "$.staleReason"))
                .isEqualTo("SOURCE_REPLACED");

        process();
        String asked = "topic-moving MarkerSecond taşınma yardımı?";
        UUID moved = uploadInto(hukuk, "tasinma.pdf", "topic-moving Tasinma yardimi on dort gundur.");
        process();
        UUID second = verifiedCard(Map.of(moved, true), asked, ANSWER_ONE);
        assertThat(send(admin, "PUT", "/v1/documents/" + moved + "/collection",
                "{\"collectionId\":\"" + ik + "\"}").statusCode()).isEqualTo(200);
        awaitState(second, "STALE");
        assertThat((String) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + second).body(), "$.staleReason"))
                .isEqualTo("SOURCE_MOVED");
    }

    @Test
    void anExpiredCard_isNotServed_goesStaleNightly_andCanBeVerifiedAgain() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(Map.of(document, true));
        QaTables.update("update qa.knowledge_card set expires_at = now() - interval '1 minute' where id = ?", card);

        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory")).as("expired: never served").isNull();
        eval.housekeeping();
        HttpResponse<String> stale = get(admin, "/v1/admin/knowledge-cards/" + card);
        assertThat((String) JsonPath.read(stale.body(), "$.state")).isEqualTo("STALE");
        assertThat((String) JsonPath.read(stale.body(), "$.staleReason")).isEqualTo("EXPIRED");

        HttpResponse<String> again = send(admin, "POST", "/v1/admin/knowledge-cards/" + card + "/verify", null);
        assertThat(again.statusCode()).as(again.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(again.body(), "$.state")).isEqualTo("VERIFIED");
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory.cardId")).isEqualTo(card.toString());
    }

    // ---------------------------------------------------------------- disputes

    @Test
    void downRatingsOfDistinctUsers_pastTheThreshold_makeTheCardDisputed() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(Map.of(document, true));
        String firstRater = "acct-know-r1-" + suffix;
        List<String> raters = List.of(token(firstRater, "/hukuk"), token("acct-know-r2-" + suffix, "/hukuk"),
                token("acct-know-r3-" + suffix, "/hukuk"));
        // The same user twice counts once.
        rateDown(raters.get(0), ask(raters.get(0), QUESTION));
        rateDown(raters.get(0), ask(raters.get(0), QUESTION));
        rateDown(raters.get(1), ask(raters.get(1), QUESTION));
        assertThat((String) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + card).body(), "$.state"))
                .isEqualTo("VERIFIED");
        assertThat((Integer) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + card).body(), "$.disputes"))
                .isEqualTo(2);

        rateDown(raters.get(2), ask(raters.get(2), QUESTION));

        HttpResponse<String> detail = get(admin, "/v1/admin/knowledge-cards/" + card);
        assertThat((String) JsonPath.read(detail.body(), "$.state")).isEqualTo("DISPUTED");
        assertThat((Integer) JsonPath.read(detail.body(), "$.disputesByDay[0].disputes")).isEqualTo(3);
        // No user-level drill-down: no account id anywhere in the card's API, and none stored with a dispute.
        assertThat(detail.body()).doesNotContain("acct-know-r");
        assertThat(QaTables.count("select count(*) from qa.knowledge_card_dispute where card_id = ?", card)).isEqualTo(3);
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory")).as("disputed: not served").isNull();
        assertThat(AuditTables.count("select count(*) from audit.event where action = 'KNOWLEDGE_CARD_DISPUTED'"))
                .isEqualTo(1);
        // KVKK erasure (ADR-0034): the subject's pseudonymous disputes go too; the card's count stays.
        HttpResponse<String> erased = send(admin, "POST", "/v1/admin/subjects/delete",
                "{\"sub\":\"" + firstRater + "\",\"confirm\":\"" + firstRater + "\"}");
        assertThat(erased.statusCode()).as(erased.body()).isEqualTo(200);
        assertThat(QaTables.count("select count(*) from qa.knowledge_card_dispute where card_id = ?", card)).isEqualTo(2);
        assertThat(QaTables.count("select dispute_count from qa.knowledge_card where id = ?", card)).isEqualTo(3);
    }

    // ---------------------------------------------------------------- golden set

    @Test
    void goldenSetCheck_recordsRecallAndDrift_andAlertsWhenSourcesAreLost() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        UUID card = verifiedCard(Map.of(document, true));
        int chatCalls = TestChatModel.INSTANCE.calls();
        int embeddings = TestEmbeddingModel.INSTANCE.calls();

        HttpResponse<String> first = send(admin, "POST", "/v1/admin/knowledge-eval/runs", null);
        assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
        assertThat((Integer) JsonPath.read(first.body(), "$.cases")).isEqualTo(1);
        assertThat((Integer) JsonPath.read(first.body(), "$.found")).isEqualTo(1);
        assertThat((Double) JsonPath.read(first.body(), "$.recallAtK")).isEqualTo(1.0);
        assertThat((Double) JsonPath.read(first.body(), "$.sourceDrift")).isEqualTo(0.0);
        assertThat((Boolean) JsonPath.read(first.body(), "$.alert")).isFalse();
        assertThat(TestChatModel.INSTANCE.calls()).as("no chat call").isEqualTo(chatCalls);
        assertThat(TestEmbeddingModel.INSTANCE.calls()).as("no embedding call: the stored vector").isEqualTo(embeddings);

        // The text goes away without the outbox noticing (a restore, a lost event): the check is the safety net.
        jdbc.update("DELETE FROM document.document_chunk WHERE document_id = ?", document);
        HttpResponse<String> second = send(admin, "POST", "/v1/admin/knowledge-eval/runs", null);
        assertThat((Integer) JsonPath.read(second.body(), "$.sourceLost")).isEqualTo(1);
        assertThat((Double) JsonPath.read(second.body(), "$.sourceDrift")).isEqualTo(1.0);
        assertThat((Boolean) JsonPath.read(second.body(), "$.alert")).isTrue();
        assertThat((String) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + card).body(), "$.staleReason"))
                .isEqualTo("SOURCE_CHANGED");
        assertThat(registry.get("verso.qa.knowledge.eval.alert").gauge().value()).isEqualTo(1.0);
        assertThat((Integer) JsonPath.read(get(admin, "/v1/admin/knowledge-eval/runs").body(), "$.page.totalElements"))
                .isEqualTo(2);

        // The lock: a RUNNING run with a live lease refuses another; one whose lease ran out is taken over.
        QaTables.update("insert into qa.eval_run (run_trigger, run_day, status, claim_token, locked_until, top_k) "
                + "values ('MANUAL', current_date, 'RUNNING', gen_random_uuid(), now() + interval '1 hour', 5)");
        assertError(send(admin, "POST", "/v1/admin/knowledge-eval/runs", null), 409, 11048);
        QaTables.update("update qa.eval_run set locked_until = now() - interval '1 minute' where status = 'RUNNING'");
        assertThat(send(admin, "POST", "/v1/admin/knowledge-eval/runs", null).statusCode()).isEqualTo(201);
        assertThat(QaTables.count("select count(*) from qa.eval_run where status = 'FAILED'")).isEqualTo(1);
        assertNoContentLogged();
    }

    // ---------------------------------------------------------------- admin API rules

    @Test
    void create_refusesForeignTurnsUnsourcedAnswersPersonalSourcesAndDuplicates() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        TestChatModel.INSTANCE.answer(ANSWER_ONE);
        HttpResponse<String> alices = ask(alice, QUESTION);
        // Another account's answer does not exist for the admin.
        assertError(create(admin, alices, true), 404, 11021);
        // A "not found" answer has nothing to verify.
        HttpResponse<String> notFound = ask(admin, "Tamamen ilgisiz bir soru?");
        assertError(create(admin, notFound, true), 409, 11042);
        // A personal document cannot back shared knowledge.
        HttpResponse<String> upload = uploadRaw(admin, null, "kisisel.pdf", "topic-personal Kisisel not on dort.");
        assertThat(upload.statusCode()).isEqualTo(201);
        process();
        TestChatModel.INSTANCE.answer("Kişisel [1].");
        assertError(create(admin, ask(admin, "topic-personal Kişisel not?"), true), 409, 11046);
        // One live card per question.
        TestChatModel.INSTANCE.answer(ANSWER_ONE);
        assertThat(create(admin, ask(admin, QUESTION), true).statusCode()).isEqualTo(201);
        assertError(create(admin, ask(admin, QUESTION), true), 409, 11047);
        assertThat(document).isNotNull();
    }

    @Test
    void verifyAndArchive_followTheStateMachine_withAuditRowsOfIdsOnly() throws Exception {
        UUID document = uploadInto(hukuk, "izin.pdf", "topic-leave Yillik izin on dort gundur.");
        process();
        TestChatModel.INSTANCE.answer(ANSWER_ONE);
        HttpResponse<String> draft = create(admin, ask(admin, QUESTION), false);
        assertThat((String) JsonPath.read(draft.body(), "$.state")).isEqualTo("DRAFT");
        UUID card = UUID.fromString(JsonPath.read(draft.body(), "$.id"));
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory")).as("a draft is not served").isNull();

        HttpResponse<String> verified = send(secondAdmin, "POST", "/v1/admin/knowledge-cards/" + card + "/verify", null);
        assertThat((String) JsonPath.read(verified.body(), "$.state")).isEqualTo("VERIFIED");
        assertError(send(secondAdmin, "POST", "/v1/admin/knowledge-cards/" + card + "/verify", null), 409, 11043);
        assertThat((List<String>) JsonPath.read(get(admin, "/v1/admin/knowledge-cards?state=VERIFIED").body(),
                "$.data[*].id")).containsExactly(card.toString());
        assertThat(QaTables.count("select count(*) from qa.golden_case where card_id = ? and active", card)).isEqualTo(1);
        // ADR-0028: an admin without a grant on the source manages the card but never sees its text.
        String outsider = TestIdp.IDP.token().subject("acct-know-admin3-" + suffix).realmRoles("verso-admin").build();
        HttpResponse<String> hidden = get(outsider, "/v1/admin/knowledge-cards/" + card);
        assertThat((Boolean) JsonPath.read(hidden.body(), "$.readable")).isFalse();
        assertThat(hidden.body()).doesNotContain("MarkerKnowledge");
        assertThat(get(outsider, "/v1/admin/knowledge-cards").body()).contains("\"readable\":false")
                .doesNotContain("MarkerKnowledge");
        assertThat((Boolean) JsonPath.read(get(admin, "/v1/admin/knowledge-cards/" + card).body(), "$.readable")).isTrue();

        HttpResponse<String> archived = send(admin, "POST", "/v1/admin/knowledge-cards/" + card + "/archive", null);
        assertThat((String) JsonPath.read(archived.body(), "$.state")).isEqualTo("ARCHIVED");
        assertError(send(admin, "POST", "/v1/admin/knowledge-cards/" + card + "/archive", null), 409, 11043);
        assertThat(QaTables.count("select count(*) from qa.golden_case where card_id = ? and active", card)).isZero();
        assertThat((Object) JsonPath.read(ask(alice, QUESTION).body(), "$.memory")).isNull();
        assertError(get(admin, "/v1/admin/knowledge-cards/" + UUID.randomUUID()), 404, 11040);

        for (String action : List.of("KNOWLEDGE_CARD_CREATED", "KNOWLEDGE_CARD_VERIFIED", "KNOWLEDGE_CARD_ARCHIVED")) {
            assertThat(AuditTables.count("select count(*) from audit.event where action = ? and target_id = ?", action,
                    card)).as(action).isEqualTo(1);
        }
        assertThat(document).isNotNull();
        assertNoContentLogged();
    }

    /** ADR-0019: every endpoint is verso-admin only; a user gets 403 ACCESS_DENIED, no token 401. */
    @Test
    void everyEndpoint_isAdminOnly() throws Exception {
        UUID any = UUID.randomUUID();
        List<String[]> endpoints = List.of(new String[] {"GET", "/v1/admin/knowledge-cards"},
                new String[] {"GET", "/v1/admin/knowledge-cards/" + any},
                new String[] {"POST", "/v1/admin/knowledge-cards"},
                new String[] {"POST", "/v1/admin/knowledge-cards/" + any + "/verify"},
                new String[] {"POST", "/v1/admin/knowledge-cards/" + any + "/archive"},
                new String[] {"GET", "/v1/admin/knowledge-eval/runs"},
                new String[] {"POST", "/v1/admin/knowledge-eval/runs"});
        String body = "{\"conversationId\":\"" + any + "\",\"turnId\":\"" + any + "\",\"verify\":true}";
        for (String[] endpoint : endpoints) {
            String payload = endpoint[1].endsWith("knowledge-cards") && endpoint[0].equals("POST") ? body : null;
            assertError(send(dave, endpoint[0], endpoint[1], payload), 403, 90101);
            HttpRequest.Builder anonymous = HttpRequest.newBuilder(URI.create(base() + endpoint[1]))
                    .timeout(Duration.ofSeconds(30)).method(endpoint[0], HttpRequest.BodyPublishers.noBody());
            assertThat(http.send(anonymous.build(), HttpResponse.BodyHandlers.ofString()).statusCode())
                    .as(endpoint[0] + " " + endpoint[1]).isEqualTo(401);
        }
        assertThat(QaTables.count("select count(*) from qa.knowledge_card")).isZero();
        assertThat(QaTables.count("select count(*) from qa.eval_run")).isZero();
        // Binding: an unknown state, a body without the turn and a bidi override are 400 before anything is read.
        assertThat(get(admin, "/v1/admin/knowledge-cards?state=NOPE").statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/admin/knowledge-cards", "{\"conversationId\":\"" + any + "\"}").statusCode())
                .isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/admin/knowledge-cards", "{\"conversationId\":\"" + any + "\",\"turnId\":\""
                + any + "\",\"question\":\"a\\u202Eb\"}").statusCode()).isEqualTo(400);
        assertError(get(admin, "/v1/admin/knowledge-cards/not-a-uuid"), 400, 90002);
    }

    // ---------------------------------------------------------------- helpers

    /** The admin asks QUESTION, the model answers ANSWER citing the given documents, and the admin verifies it. */
    private UUID verifiedCard(Map<UUID, Boolean> cited) throws Exception {
        String answer = cited.size() == 2 ? ANSWER : ANSWER_ONE;
        return verifiedCard(cited, QUESTION, answer);
    }

    private UUID verifiedCard(Map<UUID, Boolean> cited, String question, String answer) throws Exception {
        TestChatModel.INSTANCE.answer(answer);
        HttpResponse<String> asked = ask(admin, question);
        assertThat(asked.statusCode()).as(asked.body()).isEqualTo(200);
        assertThat((String) JsonPath.read(asked.body(), "$.outcome")).as(asked.body()).isEqualTo("ANSWERED");
        assertThat((List<String>) JsonPath.read(asked.body(), "$.citations[*].documentId"))
                .containsExactlyInAnyOrderElementsOf(cited.keySet().stream().map(UUID::toString).toList());
        HttpResponse<String> created = create(admin, asked, true);
        assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
        assertThat((String) JsonPath.read(created.body(), "$.state")).isEqualTo("VERIFIED");
        return UUID.fromString(JsonPath.read(created.body(), "$.id"));
    }

    private HttpResponse<String> create(String token, HttpResponse<String> answered, boolean verify) throws Exception {
        String conversation = JsonPath.read(answered.body(), "$.conversationId");
        String turn = JsonPath.read(answered.body(), "$.turnId");
        return send(token, "POST", "/v1/admin/knowledge-cards", "{\"conversationId\":\"" + conversation
                + "\",\"turnId\":\"" + turn + "\",\"verify\":" + verify + "}");
    }

    private void rateDown(String token, HttpResponse<String> answered) throws Exception {
        assertThat((Object) JsonPath.read(answered.body(), "$.memory")).as("served from the card").isNotNull();
        String conversation = JsonPath.read(answered.body(), "$.conversationId");
        String turn = JsonPath.read(answered.body(), "$.turnId");
        HttpResponse<String> rated = send(token, "POST", "/v1/conversations/" + conversation + "/turns/" + turn
                + "/feedback", "{\"rating\":\"DOWN\",\"reason\":\"INCORRECT_OR_MISSING\"}");
        assertThat(rated.statusCode()).as(rated.body()).isEqualTo(200);
    }

    private void awaitState(UUID card, String state) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (true) {
            String body = get(admin, "/v1/admin/knowledge-cards/" + card).body();
            if (state.equals(JsonPath.read(body, "$.state"))) return;
            assertThat(System.nanoTime()).as("card " + card + " becomes " + state + ": " + body).isLessThan(deadline);
            Thread.sleep(100);
        }
    }

    /** Neither the question nor the answer of a card reaches a log line (llm-rules 2.1). */
    private void assertNoContentLogged() {
        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .noneMatch(line -> line.contains("MarkerKnowledge"));
    }

    private static void assertError(HttpResponse<String> response, int status, int code) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + " "
                + response.body()).isEqualTo(status);
        assertThat(response.body()).contains("\"code\":" + code);
    }

    private static String token(String subject, String... groups) {
        var builder = TestIdp.IDP.token().subject(subject);
        if (groups.length > 0) builder.claim("groups", List.of(groups));
        return builder.build();
    }

    private UUID collection(String name) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections", "{\"name\":\"" + name + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private void grant(UUID collection, String type, String principal) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections/" + collection + "/grants",
                "{\"principalType\":\"" + type + "\",\"principal\":\"" + principal + "\"}");
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
    }

    private UUID uploadInto(UUID collection, String fileName, String text) throws Exception {
        HttpResponse<String> response = uploadRaw(admin, collection, fileName, text);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private HttpResponse<String> uploadRaw(String token, UUID collection, String fileName, String text) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages(text));
        String path = collection == null ? "/v1/documents" : "/v1/documents?collectionId=" + collection;
        return http.send(HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).header("Content-Type", body.contentType())
                .timeout(Duration.ofSeconds(30)).POST(body.body()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private void process() {
        for (int i = 0; i < 5; i++) worker.runOnce();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE status <> 'READY'", Integer.class))
                .isZero();
    }

    private HttpResponse<String> ask(String token, String question) throws Exception {
        return send(token, "POST", "/v1/questions", "{\"question\":\"" + question + "\"}");
    }

    private HttpResponse<String> stream(String token, String question) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/v1/questions/stream"))
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .header("Accept", "text/event-stream, application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"question\":\"" + question + "\"}"))
                .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String token, String path) throws Exception {
        return send(token, "GET", path, null);
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base() + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30));
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
