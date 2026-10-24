package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.jayway.jsonpath.JsonPath;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.config.DocumentProperties;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.IngestionWorker;
import com.verso.qa.service.ModelCircuitBreaker;
import com.verso.support.AuditTables;
import com.verso.support.Multipart;
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
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Shared collections and permission-aware retrieval end to end (ADR-0028) on a real port and the real database: the
 * security access matrix (group A reads A and not B; no groups, personal only; an admin manages but reads only with a
 * grant; a revoked grant is gone at the next request; a malformed group claim gives no group), the unauthorised chunk
 * that is the exact best match and still never becomes a candidate, IDOR on every new and changed endpoint, the
 * history's "not accessible" state, audit events without directory data, and the document_principal invariant under
 * concurrent uploads and grant changes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "management.server.port=0")
@VersoTestEnvironment
class CollectionAccessTest {

    /** Rows the principals should have (personal: the owner; collection: its grants) and the rows they have. */
    private static final String PRINCIPAL_DRIFT = """
            SELECT count(*) FROM (
              (SELECT d.id, 'user:' || d.account_id AS principal FROM document.document d WHERE d.collection_id IS NULL
               UNION
               SELECT d.id, g.principal FROM document.document d
               JOIN document.collection_grant g ON g.collection_id = d.collection_id
               EXCEPT SELECT document_id, principal FROM document.document_principal)
              UNION ALL
              (SELECT document_id, principal FROM document.document_principal
               EXCEPT
               (SELECT d.id, 'user:' || d.account_id FROM document.document d WHERE d.collection_id IS NULL
                UNION
                SELECT d.id, g.principal FROM document.document d
                JOIN document.collection_grant g ON g.collection_id = d.collection_id))) drift
            """;

    @Value("${local.server.port}")
    int port;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    IngestionWorker worker;

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    RetrievalRepository retrievalRepository;

    @Autowired
    DocumentProperties documentProperties;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    ModelCircuitBreaker circuit;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String adminSub = "acct-coll-admin-" + suffix;
    private final String admin = TestIdp.IDP.token().subject(adminSub).realmRoles("verso-admin").build();
    private final String aliceSub = "acct-coll-alice-" + suffix;
    /** Alice is in group /hukuk (full path, as Keycloak sends it). */
    private final String alice = token(aliceSub, "/hukuk");
    private final String bobSub = "acct-coll-bob-" + suffix;
    /** Bob is in group /ik only. */
    private final String bob = token(bobSub, "/ik");
    private final String carolSub = "acct-coll-carol-" + suffix;
    /** Carol has no groups at all. */
    private final String carol = token(carolSub);
    private Logger root;
    private ListAppender<ILoggingEvent> appender;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM document.document");
        jdbc.update("DELETE FROM document.collection");
        QaTables.reset();
        AuditTables.reset();
        TestEmbeddingModel.INSTANCE.reset();
        TestChatModel.INSTANCE.reset();
        circuit.reset();
        awaitWorkerResumed();
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

    // --- access matrix ---------------------------------------------------------------------------------------------

    @Test
    void matrix_groupAReadsAOnly_noGroupsReadsPersonalOnly_adminManagesButReadsOnlyWithAGrant() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        UUID ik = collection("İK " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        grant(ik, "GROUP", "/ik");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-contract Sözleşme fesih süresi otuz gündür.");
        UUID payroll = uploadInto(ik, "bordro.pdf", "topic-salary Bordro her ayın beşinde ödenir.");
        UUID alicePersonal = uploadPersonal(alice, "alice.pdf", "topic-remote Alice notes on remote work.");
        UUID carolPersonal = uploadPersonal(carol, "carol.pdf", "topic-remote Carol notes on remote work.");
        process();

        // Alice (/hukuk): her own document and Hukuk's, never İK's.
        assertThat(ids(get(alice, "/v1/documents"))).containsExactlyInAnyOrder(contract.toString(), alicePersonal.toString());
        assertThat(get(alice, "/v1/documents/" + contract).body()).contains("\"collectionName\":\"Hukuk " + suffix + "\"");
        assertNotFound(get(alice, "/v1/documents/" + payroll), 10001);
        assertNotFound(get(alice, "/v1/documents/" + carolPersonal), 10001);
        assertThat(ids(get(alice, "/v1/collections"))).containsExactly(hukuk.toString());
        assertNotFound(get(alice, "/v1/collections/" + ik), 10002);
        assertNotFound(get(alice, "/v1/documents?collectionId=" + ik), 10002);
        assertThat(documentsOf(reader(aliceSub, "/hukuk"), "topic-salary bordro ne zaman")).doesNotContain(payroll)
                .allMatch(id -> id.equals(contract) || id.equals(alicePersonal));
        assertThat(documentsOf(reader(aliceSub, "/hukuk"), "topic-contract fesih süresi")).contains(contract);

        // Bob (/ik): İK only.
        assertThat(ids(get(bob, "/v1/documents"))).containsExactly(payroll.toString());
        assertThat(documentsOf(reader(bobSub, "/ik"), "topic-contract fesih süresi")).containsOnly(payroll);

        // Carol, no groups: personal documents only, no collection listed.
        assertThat(ids(get(carol, "/v1/documents"))).containsExactly(carolPersonal.toString());
        assertThat(ids(get(carol, "/v1/collections"))).isEmpty();
        assertThat(documentsOf(reader(carolSub), "topic-contract fesih süresi")).containsOnly(carolPersonal);

        // The admin manages both collections (listed, not readable) and sees their documents' metadata...
        HttpResponse<String> managed = get(admin, "/v1/collections");
        assertThat(ids(managed)).containsExactlyInAnyOrder(hukuk.toString(), ik.toString());
        assertThat(JsonPath.<List<Boolean>>read(managed.body(), "$.data[*].readable")).containsOnly(false);
        assertThat(JsonPath.<List<Boolean>>read(managed.body(), "$.data[*].manageable")).containsOnly(true);
        assertThat(ids(get(admin, "/v1/documents?collectionId=" + hukuk))).containsExactly(contract.toString());
        assertThat(get(admin, "/v1/documents/" + contract).statusCode()).isEqualTo(200);
        // ...but not their content through Q&A, and still not another user's personal document.
        assertThat(ids(get(admin, "/v1/documents"))).as("the admin's own reading list").isEmpty();
        assertNotFound(get(admin, "/v1/documents/" + alicePersonal), 10001);
        TestChatModel.INSTANCE.answer("Otuz gün [1].");
        HttpResponse<String> refused = ask(admin, "topic-contract Fesih süresi kaç gün?");
        assertThat(refused.statusCode()).isEqualTo(200);
        assertThat(refused.body()).contains("\"outcome\":\"NOT_FOUND\"").doesNotContain(contract.toString());
        assertThat(TestChatModel.INSTANCE.calls()).as("no passage, no model call").isZero();

        // A grant to the admin's own sub makes the collection readable for the admin too.
        grant(hukuk, "USER", adminSub);
        HttpResponse<String> answered = ask(admin, "topic-contract Fesih süresi kaç gün?");
        assertThat(answered.body()).contains("\"outcome\":\"ANSWERED\"").contains(contract.toString())
                .contains("\"collectionName\":\"Hukuk " + suffix + "\"");
        assertThat(jdbc.queryForObject(PRINCIPAL_DRIFT, Long.class)).isZero();
    }

    /** The grant is read at every request: revoked at 12:00, the 12:00:01 request is already denied. */
    @Test
    void revokedGrant_isDeniedAtTheNextRequest_andTheHistoryShowsTheCitationAsNotAccessible() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        UUID grantId = grant(hukuk, "GROUP", "/hukuk");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-contract Sözleşme fesih süresi otuz gündür.");
        process();
        TestChatModel.INSTANCE.answer("Otuz gün [1].");
        HttpResponse<String> answer = ask(alice, "topic-contract Fesih süresi kaç gün?");
        String conversation = JsonPath.read(answer.body(), "$.conversationId");
        assertThat(answer.body()).contains(contract.toString()).contains("\"collectionId\":\"" + hukuk + "\"");

        assertThat(send(admin, "DELETE", "/v1/collections/" + hukuk + "/grants/" + grantId, null).statusCode())
                .isEqualTo(204);

        assertNotFound(get(alice, "/v1/documents/" + contract), 10001);
        assertThat(ids(get(alice, "/v1/documents"))).isEmpty();
        assertThat(documentsOf(reader(aliceSub, "/hukuk"), "topic-contract fesih süresi")).isEmpty();
        HttpResponse<String> history = get(alice, "/v1/conversations/" + conversation);
        assertThat(JsonPath.<Boolean>read(history.body(), "$.turns[0].citations[0].documentAccessible")).isFalse();
        assertThat(JsonPath.<Boolean>read(history.body(), "$.turns[0].citations[0].documentDeleted")).isFalse();
        assertThat(JsonPath.<Object>read(history.body(), "$.turns[0].citations[0].fileName")).isNull();
        assertThat(JsonPath.<Object>read(history.body(), "$.turns[0].citations[0].collectionName")).isNull();
        assertThat(history.body()).doesNotContain("sozlesme.pdf");

        // Deleted by the admin: the same citation is now "deleted".
        assertThat(send(admin, "DELETE", "/v1/documents/" + contract, null).statusCode()).isEqualTo(204);
        assertThat(JsonPath.<Boolean>read(get(alice, "/v1/conversations/" + conversation).body(),
                "$.turns[0].citations[0].documentDeleted")).isTrue();
        assertThat(jdbc.queryForObject(PRINCIPAL_DRIFT, Long.class)).isZero();
    }

    /** ADR-0019: a group claim that is not a list, and values with control characters, give no group (fail closed). */
    @Test
    void malformedGroupClaim_givesNoGroupAccess() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        grant(hukuk, "GROUP", "/hukuk\u0007");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-contract Sözleşme fesih süresi otuz gündür.");
        process();

        String notAList = TestIdp.IDP.token().subject("acct-coll-odd-" + suffix).claim("groups", Map.of("path", "/hukuk"))
                .build();
        String controlChars = token("acct-coll-ctl-" + suffix, "/hukuk\u0007");
        String nested = TestIdp.IDP.token().subject("acct-coll-nested-" + suffix)
                .claim("groups", List.of(List.of("/hukuk"))).build();
        for (String token : List.of(notAList, controlChars, nested)) {
            assertThat(ids(get(token, "/v1/documents"))).isEmpty();
            assertThat(ids(get(token, "/v1/collections"))).isEmpty();
            assertNotFound(get(token, "/v1/documents/" + contract), 10001);
        }
        assertThat(documentsOf(new Reader("acct-coll-ctl-" + suffix, List.of("/hukuk\u0007")), "topic-contract"))
                .as("Reader drops a value no token could carry").isEmpty();
    }

    // --- retrieval: the unauthorised best match ----------------------------------------------------------------------

    /**
     * The İK document's text is the question itself: identical vector (cosine 1) and every term. It is still not a
     * candidate of either list for Alice, not ranked, not counted in the term shares; Alice's weaker passage wins.
     */
    @Test
    void retrieval_whenTheUnauthorisedChunkIsTheExactBestMatch_neverReturnsOrRanksIt() throws Exception {
        String question = "Gizli bordro ZK-4471 kodu hangi ayda ödenir";
        UUID ik = collection("İK " + suffix);
        grant(ik, "GROUP", "/ik");
        UUID secret = uploadInto(ik, "gizli.pdf", question);
        UUID mine = uploadPersonal(alice, "alice.pdf", "Bordro hakkında genel bir not, ayda bir ödenir.");
        process();
        Reader aliceReader = reader(aliceSub, "/hukuk");
        Reader bobReader = reader(bobSub, "/ik");
        assertThat(retrieval.search(bobReader, question, 5).getFirst().documentId()).as("control: it is the best match")
                .isEqualTo(secret);
        assertThat(retrieval.search(bobReader, question, 5).getFirst().similarity()).isGreaterThan(0.99);

        // Every candidate of both lists, unlimited: the vector list and the full-text list hold no İK chunk.
        float[] vector = TestEmbeddingModel.INSTANCE.embed(question);
        List<Candidate> all = transaction.execute(status -> {
            retrievalRepository.prepareFilteredScan(100);
            return retrievalRepository.hybrid(aliceReader.principals().toArray(String[]::new),
                    documentProperties.embeddingModel(), vector, question, 100, 100, 60, 100);
        });
        assertThat(all).isNotEmpty().allMatch(c -> c.documentId().equals(mine));
        List<RetrievedPassage> found = retrieval.search(aliceReader, question, 5);
        assertThat(found).isNotEmpty().allMatch(p -> p.documentId().equals(mine));
        assertThat(retrievalRepository.searchableChunks(aliceReader.principals().toArray(String[]::new),
                documentProperties.embeddingModel())).as("the term-share denominator counts only Alice's chunks")
                .isEqualTo(jdbc.queryForObject("SELECT chunk_count FROM document.document WHERE id = ?", Long.class, mine));
        // And through the API: the model never sees the secret passage.
        TestChatModel.INSTANCE.answer("Ayda bir [1].");
        HttpResponse<String> answer = ask(alice, question);
        assertThat(answer.body()).doesNotContain(secret.toString()).doesNotContain("gizli.pdf");
        if (TestChatModel.INSTANCE.calls() > 0) {
            assertThat(TestChatModel.INSTANCE.lastPrompt().getContents()).doesNotContain("ZK-4471 kodu hangi ayda");
        }
    }

    // --- IDOR and roles on every new and changed endpoint ----------------------------------------------------------

    @Test
    void managementEndpoints_refuseNonAdmins_andRecordTheDenial() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        UUID grantId = grant(hukuk, "GROUP", "/hukuk");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-contract Sözleşme.");
        UUID own = uploadPersonal(alice, "alice.pdf", "topic-remote Alice.");
        process();
        String body = "{\"name\":\"X\"}";
        String grantBody = "{\"principalType\":\"GROUP\",\"principal\":\"/hukuk\"}";
        List<HttpResponse<String>> denied = List.of(
                send(alice, "POST", "/v1/collections", body),
                send(alice, "PUT", "/v1/collections/" + hukuk, body),
                send(alice, "DELETE", "/v1/collections/" + hukuk, null),
                send(alice, "GET", "/v1/collections/" + hukuk + "/grants", null),
                send(alice, "POST", "/v1/collections/" + hukuk + "/grants", grantBody),
                send(alice, "DELETE", "/v1/collections/" + hukuk + "/grants/" + grantId, null),
                send(alice, "PUT", "/v1/documents/" + own + "/collection", "{\"collectionId\":\"" + hukuk + "\"}"),
                uploadRaw(alice, hukuk, "x.pdf", "topic-remote x"));
        for (HttpResponse<String> response : denied) {
            assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri().getPath())
                    .isEqualTo(403);
            assertThat(response.body()).contains("\"code\":90101");
        }
        // A reader may not delete a collection document: 403 10004, the document stays.
        HttpResponse<String> readerDelete = send(alice, "DELETE", "/v1/documents/" + contract, null);
        assertThat(readerDelete.statusCode()).isEqualTo(403);
        assertThat(readerDelete.body()).contains("\"code\":10004");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE id = ?", Integer.class, contract))
                .isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.collection_grant", Integer.class)).isOne();
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = 'ACCESS_DENIED' "
                + "AND detail_code = 'COLLECTION_ADMIN' AND actor_sub = ?", aliceSub)).isEqualTo(6);
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = 'ACCESS_DENIED' "
                + "AND detail_code = 'DOCUMENT' AND actor_sub = ?", aliceSub)).isEqualTo(2);
    }

    @Test
    void foreignIds_areNotFound_forReadersAndAdmins() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        UUID ik = collection("İK " + suffix);
        UUID hukukGrant = grant(hukuk, "GROUP", "/hukuk");
        grant(ik, "GROUP", "/ik");
        UUID payroll = uploadInto(ik, "bordro.pdf", "topic-salary Bordro.");
        UUID bobsOwn = uploadPersonal(bob, "bob.pdf", "topic-remote Bob.");
        process();
        UUID missing = UUID.randomUUID();
        // Binding: ids are UUIDs, a body field of the wrong type is refused before any use case runs.
        assertThat(get(alice, "/v1/collections/not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(get(alice, "/v1/documents?collectionId=not-a-uuid").statusCode()).isEqualTo(400);
        assertThat(send(admin, "PUT", "/v1/documents/" + payroll + "/collection", "{\"collectionId\":\"x\"}").statusCode())
                .isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/collections", "{\"name\":\"\"}").statusCode()).isEqualTo(400);
        assertThat(http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/collections")).GET().build(),
                HttpResponse.BodyHandlers.ofString()).statusCode()).as("no token").isEqualTo(401);

        // Alice: İK's collection and document do not exist for her, whatever the verb.
        assertNotFound(get(alice, "/v1/collections/" + ik), 10002);
        assertNotFound(get(alice, "/v1/documents?collectionId=" + ik), 10002);
        assertNotFound(get(alice, "/v1/documents/" + payroll), 10001);
        assertNotFound(send(alice, "DELETE", "/v1/documents/" + payroll, null), 10001);
        assertNotFound(send(alice, "DELETE", "/v1/documents/" + bobsOwn, null), 10001);
        // The admin: a grant id of another collection, unknown collections and another user's personal document.
        assertNotFound(send(admin, "DELETE", "/v1/collections/" + ik + "/grants/" + hukukGrant, null), 10003);
        assertNotFound(send(admin, "POST", "/v1/collections/" + missing + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"/x\"}"), 10002);
        assertNotFound(send(admin, "PUT", "/v1/collections/" + missing, "{\"name\":\"Y\"}"), 10002);
        assertNotFound(send(admin, "DELETE", "/v1/collections/" + missing, null), 10002);
        assertNotFound(uploadRaw(admin, missing, "x.pdf", "topic-remote x"), 10002);
        assertNotFound(send(admin, "PUT", "/v1/documents/" + bobsOwn + "/collection",
                "{\"collectionId\":\"" + hukuk + "\"}"), 10001);
        assertNotFound(send(admin, "PUT", "/v1/documents/" + payroll + "/collection",
                "{\"collectionId\":\"" + missing + "\"}"), 10002);
        assertNotFound(send(admin, "DELETE", "/v1/documents/" + bobsOwn, null), 10001);
        assertThat(jdbc.queryForObject("SELECT collection_id FROM document.document WHERE id = ?", UUID.class, payroll))
                .isEqualTo(ik);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.collection_grant WHERE id = ?", Integer.class,
                hukukGrant)).isOne();
    }

    // --- management: CRUD, grants, move, audit ------------------------------------------------------------------------

    @Test
    void admin_managesCollectionsGrantsAndMoves_withAuditEventsAndNoDirectoryDataInLogsOrAudit() throws Exception {
        HttpResponse<String> created = send(admin, "POST", "/v1/collections",
                "{\"name\":\"  Hukuk " + suffix + "  \",\"description\":\"Sözleşmeler\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        UUID hukuk = UUID.fromString(JsonPath.read(created.body(), "$.id"));
        assertThat(created.headers().firstValue("Location")).hasValue("/v1/collections/" + hukuk);
        assertThat(created.headers().firstValue("Cache-Control").orElse("")).contains("no-store");
        assertThat(JsonPath.<String>read(created.body(), "$.name")).isEqualTo("Hukuk " + suffix);
        HttpResponse<String> duplicate = send(admin, "POST", "/v1/collections", "{\"name\":\"hukuk " + suffix + "\"}");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("\"code\":10040");
        assertThat(send(admin, "POST", "/v1/collections", "{\"name\":\"bad\\u202Ename\"}").statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/collections", "{\"name\":\"" + "x".repeat(101) + "\"}").statusCode())
                .isEqualTo(400);

        HttpResponse<String> first = send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"/hukuk\"}");
        HttpResponse<String> again = send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"/hukuk\"}");
        assertThat(first.statusCode()).isEqualTo(201);
        assertThat(again.statusCode()).as("idempotent").isEqualTo(200);
        assertThat(JsonPath.<String>read(again.body(), "$.id")).isEqualTo(JsonPath.read(first.body(), "$.id"));
        assertThat(send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"ROLE\",\"principal\":\"x\"}").statusCode()).isEqualTo(400);
        assertThat(send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                "{\"principalType\":\"GROUP\",\"principal\":\"" + "g".repeat(257) + "\"}").statusCode()).isEqualTo(400);
        assertThat(get(admin, "/v1/collections/" + hukuk + "/grants").body())
                .contains("\"principalType\":\"GROUP\"").contains("\"principal\":\"/hukuk\"");

        // The admin's own personal document moves into the collection: Alice reads it, the admin no longer.
        UUID memo = uploadPersonal(admin, "memo.pdf", "topic-contract Memo.");
        process();
        HttpResponse<String> moved = send(admin, "PUT", "/v1/documents/" + memo + "/collection",
                "{\"collectionId\":\"" + hukuk + "\"}");
        assertThat(moved.statusCode()).isEqualTo(200);
        assertThat(moved.body()).contains("\"collectionId\":\"" + hukuk + "\"");
        assertThat(ids(get(alice, "/v1/documents"))).containsExactly(memo.toString());
        assertThat(documentsOf(reader(adminSub), "topic-contract")).isEmpty();
        HttpResponse<String> notEmpty = send(admin, "DELETE", "/v1/collections/" + hukuk, null);
        assertThat(notEmpty.statusCode()).isEqualTo(409);
        assertThat(notEmpty.body()).contains("\"code\":10041");
        // Back to its uploader's personal space: Alice loses it, the admin reads it again.
        assertThat(send(admin, "PUT", "/v1/documents/" + memo + "/collection", "{\"collectionId\":null}").statusCode())
                .isEqualTo(200);
        assertThat(ids(get(alice, "/v1/documents"))).isEmpty();
        assertThat(documentsOf(reader(adminSub), "topic-contract")).containsOnly(memo);
        assertThat(jdbc.queryForObject(PRINCIPAL_DRIFT, Long.class)).isZero();

        HttpResponse<String> renamed = send(admin, "PUT", "/v1/collections/" + hukuk, "{\"name\":\"Hukuk-2 " + suffix + "\"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        UUID grantId = UUID.fromString(JsonPath.read(first.body(), "$.id"));
        assertThat(send(admin, "DELETE", "/v1/collections/" + hukuk + "/grants/" + grantId, null).statusCode())
                .isEqualTo(204);
        assertThat(send(admin, "DELETE", "/v1/collections/" + hukuk, null).statusCode()).isEqualTo(204);
        assertNotFound(get(admin, "/v1/collections/" + hukuk), 10002);

        for (String action : List.of("COLLECTION_CREATED", "COLLECTION_UPDATED", "COLLECTION_DELETED")) {
            assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = ? AND target_type = 'COLLECTION' "
                    + "AND target_id = ?::uuid AND actor_sub = ?", action, hukuk.toString(), adminSub)).as(action).isOne();
        }
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = 'COLLECTION_GRANT_ADDED' "
                + "AND detail_code = 'GROUP'")).as("the repeated grant changed nothing").isOne();
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE action = 'COLLECTION_GRANT_REMOVED' "
                + "AND detail_code = 'GROUP'")).isOne();
        assertThat(AuditTables.strings("SELECT detail_code FROM audit.event WHERE action = 'DOCUMENT_MOVED' "
                + "AND target_id = ?::uuid ORDER BY occurred_at", memo.toString()))
                .containsExactly("TO_COLLECTION", "TO_PERSONAL");
        // Directory data and free text stay out of the audit log and every log line (security-rules 4).
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE coalesce(detail_code, '') LIKE '%HUKUK%'"))
                .isZero();
        assertThat(appender.list).noneMatch(e -> e.getFormattedMessage().contains("/hukuk")
                || e.getFormattedMessage().contains("Hukuk") || e.getFormattedMessage().contains("memo.pdf"));
        assertThat(appender.list).anyMatch(e -> e.getFormattedMessage().contains("principalType=GROUP"));
    }

    /** ADR-0023's name policy covers collection documents too: names off by default, the metrics show none. */
    @Test
    void adminMetrics_doNotShowCollectionDocumentNames_whenTheNamePolicyIsOff() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        UUID contract = uploadInto(hukuk, "gizli-sozlesme.pdf", "topic-contract Sözleşme fesih süresi otuz gündür.");
        process();
        TestChatModel.INSTANCE.answer("Otuz gün [1].");
        ask(alice, "topic-contract Fesih süresi kaç gün?");
        String today = LocalDate.now(ZoneId.of("Europe/Istanbul")).toString();

        HttpResponse<String> metrics = get(admin, "/v1/admin/metrics?from=" + today + "&to=" + today);

        assertThat(metrics.statusCode()).isEqualTo(200);
        assertThat(metrics.body()).contains(contract.toString()).doesNotContain("gizli-sozlesme.pdf")
                .doesNotContain("Hukuk " + suffix);
    }

    // --- concurrency: uploads and grant changes never leave a stale principal --------------------------------------

    @Test
    void concurrentUploadsAndGrantChanges_keepDocumentPrincipalsExact() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        // Four at a time: verso.document.max-concurrent-uploads is 4 per instance.
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                results.add(pool.submit(() -> uploadRaw(admin, hukuk, "d" + n + ".pdf", "topic-remote " + n).statusCode()));
                results.add(pool.submit(() -> {
                    HttpResponse<String> added = send(admin, "POST", "/v1/collections/" + hukuk + "/grants",
                            "{\"principalType\":\"GROUP\",\"principal\":\"/gecici-" + n + "\"}");
                    if (n % 2 == 0) {
                        send(admin, "DELETE", "/v1/collections/" + hukuk + "/grants/" + JsonPath.read(added.body(), "$.id"), null);
                    }
                    return added.statusCode();
                }));
            }
            for (Future<Integer> result : results) assertThat(result.get(60, TimeUnit.SECONDS)).isIn(200, 201);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document WHERE collection_id = ?", Integer.class,
                hukuk)).isEqualTo(12);
        assertThat(jdbc.queryForObject(PRINCIPAL_DRIFT, Long.class)).as("no stale or missing principal").isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document_principal WHERE principal = 'group:/gecici-0'",
                Integer.class)).as("a revoked grant left nothing behind").isZero();
    }

    // --- defence in depth (independent review of ADR-0028) -------------------------------------------------------------

    /**
     * An upload replay never describes a document the caller can no longer read: a demoted uploader (same sub, no
     * verso-admin) whose earlier upload moved into a collection gets a safe 409, not the name of the file or collection.
     * The still-admin uploader keeps its replay (it manages that document).
     */
    @Test
    void uploadReplay_afterTheDocumentMovedOutOfReach_isAConflictAndLeaksNothing() throws Exception {
        UUID hukuk = collection("Gizli-Alan " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        UUID key = UUID.randomUUID();
        HttpResponse<String> first = uploadKeyed(admin, "gizli-memo.pdf", "topic-contract Memo.", key);
        assertThat(first.statusCode()).isEqualTo(201);
        UUID memo = UUID.fromString(JsonPath.read(first.body(), "$.id"));
        assertThat(send(admin, "PUT", "/v1/documents/" + memo + "/collection", "{\"collectionId\":\"" + hukuk + "\"}")
                .statusCode()).isEqualTo(200);
        String demoted = TestIdp.IDP.token().subject(adminSub).build();

        HttpResponse<String> replay = uploadKeyed(demoted, "baska.pdf", "topic-contract Baska.", key);
        assertThat(replay.statusCode()).as(replay.body()).isEqualTo(409);
        assertThat(replay.body()).contains("\"code\":10016").doesNotContain("gizli-memo.pdf").doesNotContain("Gizli-Alan")
                .doesNotContain(memo.toString());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.document", Integer.class)).isOne();

        HttpResponse<String> adminReplay = uploadKeyed(admin, "baska.pdf", "topic-contract Baska.", key);
        assertThat(adminReplay.statusCode()).as(adminReplay.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(adminReplay.body(), "$.id")).isEqualTo(memo.toString());

        // Back in the personal space the demoted uploader reads it again, so the replay works for them.
        assertThat(send(admin, "PUT", "/v1/documents/" + memo + "/collection", "{\"collectionId\":null}").statusCode())
                .isEqualTo(200);
        HttpResponse<String> readable = uploadKeyed(demoted, "baska.pdf", "topic-contract Baska.", key);
        assertThat(readable.statusCode()).as(readable.body()).isEqualTo(201);
        assertThat(JsonPath.<String>read(readable.body(), "$.id")).isEqualTo(memo.toString());
    }

    /**
     * The listing of one collection filters the documents by the reader's principals as well as checking the
     * collection: a document of a readable collection without the reader's principal (drift) is not listed or counted.
     */
    @Test
    void listByCollection_reappliesTheReadablePredicate_forNonAdminsOnly() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        grant(hukuk, "GROUP", "/hukuk");
        UUID shown = uploadInto(hukuk, "a.pdf", "topic-contract A.");
        UUID drifted = uploadInto(hukuk, "b.pdf", "topic-contract B.");
        jdbc.update("DELETE FROM document.document_principal WHERE document_id = ? AND principal = 'group:/hukuk'", drifted);

        HttpResponse<String> aliceList = get(alice, "/v1/documents?collectionId=" + hukuk);
        assertThat(ids(aliceList)).containsExactly(shown.toString());
        assertThat(JsonPath.<Number>read(aliceList.body(), "$.totalElements").intValue()).isEqualTo(1);
        assertThat(ids(get(admin, "/v1/documents?collectionId=" + hukuk))).containsExactlyInAnyOrder(shown.toString(),
                drifted.toString());
        // A collection the caller does not read stays a 404 (the first layer).
        assertNotFound(get(bob, "/v1/documents?collectionId=" + hukuk), 10002);
    }

    /**
     * Principals are typed: a group literally named "user:&lt;sub&gt;" is the principal "group:user:&lt;sub&gt;" and never
     * reaches the personal documents or direct user grants of that sub.
     */
    @Test
    void groupNamedLikeAUserPrincipal_neverMatchesTheUser() throws Exception {
        UUID personal = uploadPersonal(bob, "bob.pdf", "topic-remote Bob personal.");
        UUID direct = collection("Dogrudan " + suffix);
        grant(direct, "USER", bobSub);
        UUID shared = uploadInto(direct, "ortak.pdf", "topic-remote Ortak.");
        process();
        String mallory = token("acct-coll-mallory-" + suffix, "user:" + bobSub);

        assertThat(ids(get(mallory, "/v1/documents"))).isEmpty();
        assertThat(ids(get(mallory, "/v1/collections"))).isEmpty();
        assertNotFound(get(mallory, "/v1/documents/" + personal), 10001);
        assertNotFound(get(mallory, "/v1/documents/" + shared), 10001);
        assertNotFound(get(mallory, "/v1/documents?collectionId=" + direct), 10002);
        Reader confused = reader("acct-coll-mallory-" + suffix, "user:" + bobSub);
        assertThat(confused.principals()).containsExactly("user:acct-coll-mallory-" + suffix, "group:user:" + bobSub);
        assertThat(documentsOf(confused, "topic-remote")).isEmpty();
        // Bob himself still reads both.
        assertThat(ids(get(bob, "/v1/documents"))).containsExactlyInAnyOrder(personal.toString(), shared.toString());
        // And the other way round: a group grant named "user:<sub>" is not a direct grant to that sub.
        UUID byGroup = collection("Gruplu " + suffix);
        grant(byGroup, "GROUP", "user:" + carolSub);
        UUID carols = uploadInto(byGroup, "gruplu.pdf", "topic-remote Gruplu.");
        process();
        assertNotFound(get(carol, "/v1/documents/" + carols), 10001);
        assertThat(documentsOf(reader(carolSub), "topic-remote Gruplu")).isEmpty();
        assertThat(ids(get(token("acct-coll-carol2-" + suffix, "user:" + carolSub), "/v1/documents")))
                .containsExactly(carols.toString());
    }

    /** ADR-0028: a group value is compared exactly, case and whitespace included (a typo closes, never opens). */
    @Test
    void groupValue_isComparedExactly_caseAndWhitespace() throws Exception {
        UUID hukuk = collection("Hukuk " + suffix);
        grant(hukuk, "GROUP", "/Hukuk");
        UUID contract = uploadInto(hukuk, "sozlesme.pdf", "topic-contract Sozlesme.");
        process();

        for (String wrong : List.of("/hukuk", "/HUKUK", "/Hukuk ", " /Hukuk", "Hukuk")) {
            String token = token("acct-coll-case-" + suffix, wrong);
            assertThat(ids(get(token, "/v1/documents"))).as("[" + wrong + "]").isEmpty();
            assertNotFound(get(token, "/v1/documents/" + contract), 10001);
            assertNotFound(get(token, "/v1/documents?collectionId=" + hukuk), 10002);
        }
        assertThat(documentsOf(reader("acct-coll-case-" + suffix, "/hukuk"), "topic-contract")).isEmpty();
        String exact = token("acct-coll-case-" + suffix, "/Hukuk");
        assertThat(ids(get(exact, "/v1/documents"))).containsExactly(contract.toString());
        assertThat(documentsOf(reader("acct-coll-case-" + suffix, "/Hukuk"), "topic-contract")).containsOnly(contract);
    }

    // --- helpers -----------------------------------------------------------------------------------------------------

    /** A personal upload carrying X-Idempotency-Key. */
    private HttpResponse<String> uploadKeyed(String token, String fileName, String text, UUID key) throws Exception {
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages(text));
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/documents"))
                .header("Authorization", "Bearer " + token).header("Content-Type", body.contentType())
                .header("X-Idempotency-Key", key.toString()).timeout(Duration.ofSeconds(30)).POST(body.body()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Waits (bounded) until a model pause left over from an earlier test has expired. The pause is clock-based and
     * has no reset, so the wait is a deadline check with a timeout failure, not an unbounded spin.
     */
    private void awaitWorkerResumed() {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (worker.isPaused()) {
            assertThat(System.nanoTime()).as("ingestion worker stays paused").isLessThan(deadline);
            java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(50));
        }
    }

    private static String token(String subject, String... groups) {
        var builder = TestIdp.IDP.token().subject(subject);
        if (groups.length > 0) builder.claim("groups", List.of(groups));
        return builder.build();
    }

    private static Reader reader(String subject, String... groups) {
        return new Reader(subject, List.of(groups));
    }

    private List<UUID> documentsOf(Reader reader, String question) {
        return retrieval.search(reader, question, 10).stream().map(RetrievedPassage::documentId).distinct().toList();
    }

    private UUID collection(String name) throws Exception {
        HttpResponse<String> response = send(admin, "POST", "/v1/collections", "{\"name\":\"" + name + "\"}");
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private UUID grant(UUID collection, String type, String principal) throws Exception {
        String value = principal.replace("\u0007", "\\u0007");
        HttpResponse<String> response = send(admin, "POST", "/v1/collections/" + collection + "/grants",
                "{\"principalType\":\"" + type + "\",\"principal\":\"" + value + "\"}");
        if (principal.contains("\u0007")) {
            assertThat(response.statusCode()).as("a value no token can carry is refused").isEqualTo(400);
            return null;
        }
        assertThat(response.statusCode()).as(response.body()).isIn(200, 201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private UUID uploadInto(UUID collection, String fileName, String text) throws Exception {
        HttpResponse<String> response = uploadRaw(admin, collection, fileName, text);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private UUID uploadPersonal(String token, String fileName, String text) throws Exception {
        HttpResponse<String> response = uploadRaw(token, null, fileName, text);
        assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
        return UUID.fromString(JsonPath.read(response.body(), "$.id"));
    }

    private HttpResponse<String> uploadRaw(String token, UUID collection, String fileName, String text) throws Exception {
        // The test PDF font (Helvetica, WinAnsi) has no ı, ş, ğ, İ: the texts are folded to ASCII for the file.
        String ascii = text.replace('ı', 'i').replace('ş', 's').replace('ğ', 'g').replace('İ', 'I').replace('Ş', 'S')
                .replace('Ğ', 'G');
        Multipart body = Multipart.pdf(fileName, TestPdfs.pages(ascii));
        String path = collection == null ? "/v1/documents" : "/v1/documents?collectionId=" + collection;
        return http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
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

    private HttpResponse<String> get(String token, String path) throws Exception {
        return send(token, "GET", path, null);
    }

    private HttpResponse<String> send(String token, String method, String path, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("Authorization", "Bearer " + token).timeout(Duration.ofSeconds(30));
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            request.header("Content-Type", "application/json").method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> ids(HttpResponse<String> response) {
        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        return JsonPath.read(response.body(), "$.data[*].id");
    }

    private static void assertNotFound(HttpResponse<String> response, int code) {
        assertThat(response.statusCode()).as(response.request().method() + " " + response.request().uri() + " "
                + response.body()).isEqualTo(404);
        assertThat(response.body()).contains("\"code\":" + code);
    }
}
