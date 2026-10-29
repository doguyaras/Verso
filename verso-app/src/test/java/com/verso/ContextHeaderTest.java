package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.RetrievedPassage;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.config.DocumentProperties;
import com.verso.document.repository.ContextIndexRepository;
import com.verso.document.repository.DocumentRepository;
import com.verso.document.repository.DocumentRow;
import com.verso.document.repository.IngestionRepository.StoredChunk;
import com.verso.document.repository.RetrievalRepository;
import com.verso.document.repository.RetrievalRepository.Candidate;
import com.verso.document.testing.TestPdfs;
import com.verso.document.worker.ChunkContext;
import com.verso.document.worker.ContextHeaderBackfillJob;
import com.verso.document.worker.IngestionWorker;
import com.verso.support.TestEmbeddingModel;
import com.verso.support.VersoPostgres;
import com.verso.support.VersoTestEnvironment;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contextual chunk headers (ADR-0061) against the real database: a question that names a regulation by the
 * abbreviation in its title finds that regulation's chunks through the header's full-text vector, also on a page that
 * never repeats the abbreviation; the stored text and every passage stay the chunk's own text; the backfill re-indexes
 * a document indexed before the headers under the same chunk ids, once, also with two instances at the same time, and
 * leaves a document of another embedding model alone. TestPdfs writes ASCII only (Helvetica), so the Turkish names are
 * written without Turkish letters.
 */
@SpringBootTest
@VersoTestEnvironment
class ContextHeaderTest {

    private static final String ARTICLE = "Mudur\nMADDE 7 - (1) Mudur, Rektor tarafindan uc yil icin gorevlendirilir.";

    @Autowired
    DocumentRepository documents;

    @Autowired
    IngestionWorker worker;

    @Autowired
    ContextHeaderBackfillJob backfill;

    @Autowired
    DocumentRetrieval retrieval;

    @Autowired
    RetrievalRepository retrievalRepository;

    @Autowired
    ContextIndexRepository contextIndex;

    @Autowired
    DocumentProperties documentProperties;

    @Autowired
    TransactionTemplate transaction;

    @Autowired
    JdbcTemplate jdbc;

    private final String alice = "acct-ctx-" + UUID.randomUUID();

    @BeforeEach
    void clean() throws InterruptedException {
        jdbc.update("DELETE FROM document.document");
        TestEmbeddingModel.INSTANCE.reset();
        while (worker.isPaused()) Thread.sleep(50);
    }

    /** The disambiguation case of the external eval: two centres, the same article, only the title tells them apart. */
    @Test
    void search_whenTheQuestionNamesTheTitleAbbreviation_findsThatRegulationsChunk() {
        // HUZEM first: on equal scores the older chunk id would win, so only the header can put HÜBAUM's page 2 ahead.
        UUID huzem = ready("huzem.pdf", "UZAKTAN EGITIM MERKEZI (HUZEM) YONETMELIGI", ARTICLE);
        UUID hubaum = ready("hubaum.pdf", "BIYOMUHENDISLIK ARASTIRMA MERKEZI (HUBAUM) YONETMELIGI", ARTICLE);
        String question = "HUBAUM muduru kac yil gorev yapar";

        // The full-text list alone (no vector candidates): page 2 of both says the same; the header tells them apart.
        float[] vector = TestEmbeddingModel.INSTANCE.embed(question);
        List<Candidate> lexical = transaction.execute(status -> {
            retrievalRepository.prepareFilteredScan(10);
            return retrievalRepository.hybrid(new String[]{"user:" + alice}, documentProperties.embeddingModel(),
                    vector, question, 0, 20, 60, 10);
        });
        List<UUID> pageTwoOrder = lexical.stream().filter(c -> c.pageNumber() == 2).map(Candidate::documentId).toList();
        assertThat(pageTwoOrder).containsExactly(hubaum, huzem);
        Candidate hubaumPage2 = lexical.stream()
                .filter(c -> c.documentId().equals(hubaum) && c.pageNumber() == 2).findFirst().orElseThrow();
        assertThat(hubaumPage2.content()).as("the passage is the chunk's text, never the header")
                .contains("Rektor tarafindan uc yil").doesNotContain("HUBAUM", "Yonetmeligi");

        // Through the service: the same passage text, no header in any passage.
        List<RetrievedPassage> found = retrieval.search(alice, question, 5);
        assertThat(found).filteredOn(p -> p.pageNumber() == 2)
                .allSatisfy(p -> assertThat(p.content()).doesNotContain("HUBAUM", "HUZEM", "Yonetmeligi"));
        assertThat(found).anySatisfy(p -> assertThat(p.documentId()).isEqualTo(hubaum));
    }

    /** The header is stored for the index only: content, header and header vector agree with V7's checks. */
    @Test
    void ingestion_storesTheHeaderBesideTheUnchangedText() {
        UUID id = ready("hubaum.pdf", "BIYOMUHENDISLIK ARASTIRMA MERKEZI (HUBAUM) YONETMELIGI", ARTICLE);

        Map<String, Object> page2 = jdbc.queryForMap("SELECT content, context_header, header_vector::text AS terms "
                + "FROM document.document_chunk WHERE document_id = ? AND page_number = 2", id);
        assertThat((String) page2.get("content")).contains("Rektor tarafindan uc yil").doesNotContain("HUBAUM");
        assertThat((String) page2.get("context_header"))
                .isEqualTo("Biyomuhendislik Arastirma Merkezi (HUBAUM) Yonetmeligi\nMadde 7 – Mudur")
                .hasSizeLessThanOrEqualTo(ChunkContext.MAX_HEADER_CHARS);
        assertThat((String) page2.get("terms")).contains("hubaum");
        assertThat(jdbc.queryForObject("SELECT context_version FROM document.document WHERE id = ?", Integer.class, id))
                .isEqualTo(ChunkContext.indexVersion(true));
        assertThat(jdbc.queryForObject("SELECT index_title FROM document.document WHERE id = ?", String.class, id))
                .isEqualTo("Biyomuhendislik Arastirma Merkezi (HUBAUM) Yonetmeligi");
    }

    /**
     * An installation upgraded to ADR-0061: its chunks have no header. The backfill re-embeds and replaces them under
     * the same ids (the qa module refers to chunks by id), the text unchanged, the system model extracted again; a
     * second run finds nothing.
     */
    @Test
    void backfill_whenADocumentWasIndexedWithoutHeaders_reindexesItUnderTheSameIds() throws Exception {
        assertThat(backfill.runOnce()).as("nothing pending in an empty schema").isZero();
        UUID id = ready("hubaum.pdf", "BIYOMUHENDISLIK ARASTIRMA MERKEZI (HUBAUM) YONETMELIGI", ARTICLE);
        asLegacy(id);
        List<Map<String, Object>> before = chunks(id);
        assertThat(before).allSatisfy(c -> assertThat(c.get("context_header")).isNull());

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        try {
            assertThat(backfill.runOnce()).isEqualTo(1);
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        // llm-rules 2.1: ids and counts only; neither title, header, text nor file name reaches a log line.
        assertThat(appender.list).anySatisfy(e -> assertThat(e.getFormattedMessage())
                .contains("Context header backfill re-indexed: documentId=" + id));
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage() + event.getMDCPropertyMap())
                    .doesNotContain("HUBAUM", "Hubaum", "Mudur", "Rektor", "hubaum.pdf");
        }

        List<Map<String, Object>> after = chunks(id);
        assertThat(after).extracting(c -> c.get("id")).containsExactlyElementsOf(before.stream().map(c -> c.get("id")).toList());
        assertThat(after).extracting(c -> c.get("content"))
                .containsExactlyElementsOf(before.stream().map(c -> c.get("content")).toList());
        assertThat(after).allSatisfy(c -> assertThat((String) c.get("context_header")).contains("(HUBAUM)"));
        assertThat(jdbc.queryForObject("SELECT context_version FROM document.document WHERE id = ?", Integer.class, id))
                .isEqualTo(ChunkContext.indexVersion(true));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM document.system_model_state WHERE document_id = ?",
                Long.class, id)).as("the system model read from the chunks is extracted again").isEqualTo(1);
        assertThat(backfill.runOnce()).as("idempotent").isZero();
        assertThat(retrieval.search(alice, "HUBAUM muduru kac yil", 5)).isNotEmpty();
    }

    /** Two instances: the lease and SKIP LOCKED give each document to one of them; every document is done once. */
    @Test
    void backfill_whenTwoInstancesRunAtOnce_reindexesEveryDocumentOnce() throws Exception {
        UUID first = ready("a.pdf", "BIRINCI MERKEZ (BRM) YONETMELIGI", ARTICLE);
        UUID second = ready("b.pdf", "IKINCI MERKEZ (IKM) YONETMELIGI", ARTICLE);
        asLegacy(first);
        asLegacy(second);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(backfill::runOnce);
            var b = pool.submit(backfill::runOnce);
            assertThat(a.get(60, TimeUnit.SECONDS) + b.get(60, TimeUnit.SECONDS)).isEqualTo(2);
        } finally {
            pool.shutdownNow();
        }
        assertThat(backfill.runOnce()).isZero();
    }

    /**
     * A worker whose lease ran out writes after another instance finished the document, or after it was re-ingested:
     * the re-check under the row lock finds it current (or its chunk ids changed) and nothing is replaced.
     */
    @Test
    void replace_whenTheDocumentWasFinishedOrReingestedMeanwhile_writesNothing() throws Exception {
        UUID id = ready("a.pdf", "BIRINCI MERKEZ (BRM) YONETMELIGI", ARTICLE);
        asLegacy(id);
        List<Map<String, Object>> rows = chunks(id);
        List<UUID> ids = rows.stream().map(c -> (UUID) c.get("id")).toList();
        List<StoredChunk> stale = rows.stream().map(c -> new StoredChunk((Integer) c.get("page_number"),
                (Integer) c.get("chunk_index"), (String) c.get("content"), unitVector(), "Stale")).toList();
        int version = ChunkContext.indexVersion(true);
        String model = documentProperties.embeddingModel();

        assertThat(backfill.runOnce()).as("the other instance").isEqualTo(1);
        assertThat(replaceAs(id, version, model, ids, stale)).isFalse();
        assertThat(chunks(id)).allSatisfy(c -> assertThat((String) c.get("context_header")).doesNotContain("Stale"));

        // Re-ingested: new chunk ids; a writer holding the old ones changes nothing, even with the version pending.
        asLegacy(id);
        jdbc.update("UPDATE document.document SET status = 'PENDING', next_attempt_at = now() WHERE id = ?", id);
        worker.runOnce();
        asLegacy(id);
        assertThat(chunks(id)).extracting(c -> c.get("id")).doesNotContainAnyElementsOf(ids);
        assertThat(replaceAs(id, version, model, ids, stale)).isFalse();
    }

    /** The stale worker's final transaction. */
    private boolean replaceAs(UUID id, int version, String model, List<UUID> ids, List<StoredChunk> chunks) {
        Boolean replaced = transaction.execute(s -> contextIndex.replace(id, version, model, "Stale", ids, chunks));
        return Boolean.TRUE.equals(replaced);
    }

    private static float[] unitVector() {
        float[] vector = new float[TestEmbeddingModel.DIMENSIONS];
        vector[0] = 1f;
        return vector;
    }

    /** Another embedding model is a model migration (llm-rules 6.2), not something the backfill starts on its own. */
    @Test
    void backfill_whenTheDocumentHasAnotherEmbeddingModel_leavesItAlone() throws Exception {
        UUID id = ready("a.pdf", "BIRINCI MERKEZ (BRM) YONETMELIGI", ARTICLE);
        asLegacy(id);
        superuser("UPDATE document.document SET embedding_model = 'old-model' WHERE id = ?", id);

        assertThat(backfill.runOnce()).isZero();
        assertThat(chunks(id)).allSatisfy(c -> assertThat(c.get("context_header")).isNull());
    }

    private UUID ready(String fileName, String... pages) {
        DocumentRow row = documents.insert(alice, fileName, DocumentFormat.PDF, 10, null, Instant.now());
        documents.insertFile(row.id(), TestPdfs.pages(pages));
        worker.runOnce();
        assertThat(documents.find(alice, row.id()).orElseThrow().status().name()).isEqualTo("READY");
        return row.id();
    }

    private List<Map<String, Object>> chunks(UUID id) {
        return jdbc.queryForList("SELECT id, page_number, chunk_index, content, context_header FROM document.document_chunk "
                + "WHERE document_id = ? ORDER BY chunk_index", id);
    }

    /** As indexed before V7: no header, no version. The application role cannot update chunks (V1). */
    private static void asLegacy(UUID id) throws Exception {
        superuser("UPDATE document.document_chunk SET context_header = NULL, header_vector = NULL WHERE document_id = ?",
                id);
        superuser("UPDATE document.document SET context_version = NULL, index_title = NULL WHERE id = ?", id);
    }

    private static void superuser(String sql, UUID id) throws Exception {
        try (Connection admin = DriverManager.getConnection(VersoPostgres.POSTGRES.getJdbcUrl(),
                VersoPostgres.POSTGRES.getUsername(), VersoPostgres.POSTGRES.getPassword());
             PreparedStatement update = admin.prepareStatement(sql)) {
            update.setObject(1, id);
            update.executeUpdate();
        }
    }
}
