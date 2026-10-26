package com.verso.qa.repository;

import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.api.dto.KnowledgeHealthRunResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The diagnostics runs, their clusters and the question vectors (ADR-0052). The run row is the job's distributed lock
 * (one RUNNING row, a claim token and a lease), the same pattern as the golden-set check (KnowledgeEvalRepository).
 *
 * <p>Privacy: every statement that groups turns counts distinct accounts in the database and returns only groups at or
 * above k; no method returns an account id. Question texts leave this class only to be embedded (job) or under the
 * question-text policy (report), never to a log.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeDiagnosticsRepository {

    private static final String RUN_COLUMNS = "id, run_trigger, status, window_from, window_to, questions, embedded, "
            + "clusters, contradictions_checked, started_at, finished_at";

    /** A finished run's counts. */
    public record Totals(int questions, int embedded, int clusters, int contradictionsChecked, UUID cursor,
                         String summaryJson) {
    }

    /** A succeeded run as the report reads it. */
    public record RunRow(UUID id, LocalDate windowFrom, LocalDate windowTo, Instant finishedAt, int questions,
                         int clusters, int contradictionsChecked, int minAskers, String summaryJson) {
    }

    /** A stored cluster. */
    public record ClusterRow(UUID id, int position, float[] centroid, String embeddingModel, int turns, int askers,
                             Integer previousTurns, ClusterTrend trend, List<String> keywords,
                             List<byte[]> representativeHashes) {

        @Override
        public String toString() {
            return "ClusterRow[id=" + id + ", turns=" + turns + ", askers=" + askers + "]";
        }
    }

    /** Citations of one document in a window, with the number of distinct accounts behind them (k-anonymous). */
    public record DocumentCitations(UUID documentId, long citations, int askers) {
    }

    /** Cited documents and distinct accounts per collection in a window. */
    public record CollectionUse(UUID collectionId, int citedDocuments, int askers) {
    }

    /** Down ratings per collection and reason (k-anonymous). */
    public record FeedbackCell(UUID collectionId, String reason, long ratings, int askers) {
    }

    private final QaDatabase db;

    public KnowledgeDiagnosticsRepository(QaDatabase db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- the run lock

    public int expireLeases(Instant now) {
        return db.jdbc().sql("UPDATE qa.diagnostic_run SET status = 'FAILED', claim_token = NULL, locked_until = NULL, "
                        + "finished_at = :now WHERE status = 'RUNNING' AND locked_until < :now")
                .param("now", Timestamp.from(now)).update();
    }

    /** The new run, or empty when another run is RUNNING (or today's scheduled run exists). */
    public Optional<UUID> claim(String trigger, LocalDate runDay, LocalDate from, LocalDate to, int minAskers,
                                UUID claimToken, Instant lockedUntil, Instant now) {
        return db.jdbc().sql("INSERT INTO qa.diagnostic_run (run_trigger, run_day, status, claim_token, locked_until, "
                        + "window_from, window_to, min_askers, started_at) VALUES (:trigger, :day, 'RUNNING', :token, "
                        + ":until, :from, :to, :k, :now) ON CONFLICT DO NOTHING RETURNING id")
                .param("trigger", trigger).param("day", runDay).param("token", claimToken)
                .param("until", Timestamp.from(lockedUntil)).param("from", from).param("to", to).param("k", minAskers)
                .param("now", Timestamp.from(now))
                .query(UUID.class).optional();
    }

    public boolean renew(UUID runId, UUID claimToken, Instant lockedUntil) {
        return db.jdbc().sql("UPDATE qa.diagnostic_run SET locked_until = :until WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("until", Timestamp.from(lockedUntil)).update() == 1;
    }

    /** Whether this instance still holds the run (the lease was not taken over). */
    public boolean holds(UUID runId, UUID claimToken) {
        return db.jdbc().sql("SELECT count(*) FROM qa.diagnostic_run WHERE id = :run AND claim_token = :token "
                        + "AND status = 'RUNNING'")
                .param("run", runId).param("token", claimToken).query(Long.class).single() == 1;
    }

    public boolean finish(UUID runId, UUID claimToken, Totals totals, Instant now) {
        return db.jdbc().sql("UPDATE qa.diagnostic_run SET status = 'SUCCEEDED', claim_token = NULL, locked_until = NULL, "
                        + "questions = :questions, embedded = :embedded, clusters = :clusters, "
                        + "contradictions_checked = :checked, contradiction_cursor = :cursor, "
                        + "summary = CAST(:summary AS jsonb), finished_at = :now WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("questions", totals.questions())
                .param("embedded", totals.embedded()).param("clusters", totals.clusters())
                .param("checked", totals.contradictionsChecked()).param("cursor", totals.cursor())
                .param("summary", totals.summaryJson()).param("now", Timestamp.from(now)).update() == 1;
    }

    public void fail(UUID runId, UUID claimToken, Instant now) {
        db.jdbc().sql("UPDATE qa.diagnostic_run SET status = 'FAILED', claim_token = NULL, locked_until = NULL, "
                        + "finished_at = :now WHERE id = :run AND claim_token = :token")
                .param("run", runId).param("token", claimToken).param("now", Timestamp.from(now)).update();
    }

    /** The contradiction cursor of the latest succeeded run (null: start from the first chunk). */
    public Optional<UUID> lastCursor() {
        return db.jdbc().sql("SELECT contradiction_cursor FROM qa.diagnostic_run WHERE status = 'SUCCEEDED' "
                        + "ORDER BY finished_at DESC, id DESC LIMIT 1")
                .query((rs, n) -> Optional.ofNullable(rs.getObject(1, UUID.class))).optional().flatMap(o -> o);
    }

    public Optional<KnowledgeHealthRunResponse> find(UUID runId) {
        return db.jdbc().sql("SELECT " + RUN_COLUMNS + " FROM qa.diagnostic_run WHERE id = :run").param("run", runId)
                .query(KnowledgeDiagnosticsRepository::run).optional();
    }

    public List<KnowledgeHealthRunResponse> list(int page, int size) {
        return db.jdbc().sql("SELECT " + RUN_COLUMNS + " FROM qa.diagnostic_run ORDER BY started_at DESC, id DESC "
                        + "LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", (long) page * size).query(KnowledgeDiagnosticsRepository::run).list();
    }

    public long count() {
        return db.jdbc().sql("SELECT count(*) FROM qa.diagnostic_run").query(Long.class).single();
    }

    /** The latest succeeded run whose window ends in from..to. */
    public Optional<RunRow> latestSucceeded(LocalDate from, LocalDate to) {
        return db.jdbc().sql("SELECT id, window_from, window_to, finished_at, questions, clusters, "
                        + "contradictions_checked, min_askers, summary::text FROM qa.diagnostic_run "
                        + "WHERE status = 'SUCCEEDED' AND window_to BETWEEN :from AND :to "
                        + "ORDER BY window_to DESC, finished_at DESC, id DESC LIMIT 1")
                .param("from", from).param("to", to)
                .query((rs, n) -> new RunRow(rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getObject(3, LocalDate.class), rs.getTimestamp(4).toInstant(), rs.getInt(5), rs.getInt(6),
                        rs.getInt(7), rs.getInt(8), rs.getString(9)))
                .optional();
    }

    /** The trend series: succeeded runs ending in from..to, oldest first, at most {@code limit}. */
    public List<Object[]> series(LocalDate from, LocalDate to, int limit) {
        return db.jdbc().sql("SELECT id, window_to, questions, clusters FROM qa.diagnostic_run "
                        + "WHERE status = 'SUCCEEDED' AND window_to BETWEEN :from AND :to "
                        + "ORDER BY window_to, finished_at, id LIMIT :limit")
                .param("from", from).param("to", to).param("limit", limit)
                .query((rs, n) -> new Object[] {rs.getObject(1, UUID.class), rs.getObject(2, LocalDate.class),
                        rs.getInt(3), rs.getInt(4)})
                .list();
    }

    /** The latest succeeded run before this one (the trend's baseline). */
    public Optional<UUID> previousSucceeded(UUID runId) {
        return db.jdbc().sql("SELECT id FROM qa.diagnostic_run WHERE status = 'SUCCEEDED' AND id <> :run "
                        + "ORDER BY finished_at DESC, id DESC LIMIT 1")
                .param("run", runId).query(UUID.class).optional();
    }

    /** Runs started before the cutoff (with their clusters, by cascade); a bounded batch. */
    public int purge(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.diagnostic_run WHERE id IN (SELECT id FROM qa.diagnostic_run "
                        + "WHERE started_at < :cutoff AND status <> 'RUNNING' ORDER BY started_at LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    // ---------------------------------------------------------------- uncertain questions and their vectors

    /** The uncertain turns of a day range, newest first, at most {@code limit}: NOT_FOUND, UNCITED or flagged. */
    public List<UUID> uncertainTurns(LocalDate from, LocalDate to, int limit) {
        return db.jdbc().sql("SELECT id FROM qa.turn WHERE day BETWEEN :from AND :to "
                        + "AND (outcome IN ('NOT_FOUND', 'UNCITED') OR unverified_sentences > 0) "
                        + "ORDER BY day DESC, id DESC LIMIT :limit")
                .param("from", from).param("to", to).param("limit", limit).query(UUID.class).list();
    }

    /** The stored vectors of these turns made by {@code model}. */
    public Map<UUID, float[]> vectors(Collection<UUID> turnIds, String model) {
        Map<UUID, float[]> vectors = new LinkedHashMap<>();
        if (turnIds.isEmpty()) return vectors;
        db.jdbc().sql("SELECT turn_id, embedding::text FROM qa.turn_question_vector "
                        + "WHERE turn_id = ANY(CAST(:turns AS uuid[])) AND embedding_model = :model")
                .param("turns", ids(turnIds)).param("model", model)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class),
                        KnowledgeCardRepository.parseVector(rs.getString(2))))
                .list().forEach(e -> vectors.put(e.getKey(), e.getValue()));
        return vectors;
    }

    /** The question texts of these turns, to be embedded (never logged, never stored elsewhere). */
    public Map<UUID, String> questions(Collection<UUID> turnIds) {
        Map<UUID, String> questions = new LinkedHashMap<>();
        if (turnIds.isEmpty()) return questions;
        db.jdbc().sql("SELECT id, question FROM qa.turn WHERE id = ANY(CAST(:turns AS uuid[])) ORDER BY id")
                .param("turns", ids(turnIds))
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2)))
                .list().forEach(e -> questions.put(e.getKey(), e.getValue()));
        return questions;
    }

    /**
     * Stores a question vector; a vector of an older model is replaced (the turn may be gone meanwhile: then nothing
     * is stored).
     */
    public void storeVector(UUID turnId, float[] vector, String model, Instant now) {
        db.jdbc().sql("DELETE FROM qa.turn_question_vector WHERE turn_id = :turn AND embedding_model <> :model")
                .param("turn", turnId).param("model", model).update();
        db.jdbc().sql("INSERT INTO qa.turn_question_vector (turn_id, embedding, embedding_model, created_at) "
                        + "SELECT id, CAST(:embedding AS extensions.vector), :model, :now FROM qa.turn WHERE id = :turn "
                        + "ON CONFLICT DO NOTHING")
                .param("turn", turnId).param("embedding", KnowledgeCardRepository.vectorLiteral(vector))
                .param("model", model).param("now", Timestamp.from(now)).update();
    }

    /** Distinct accounts behind these turns (the count only). */
    public int askers(Collection<UUID> turnIds) {
        if (turnIds.isEmpty()) return 0;
        return db.jdbc().sql("SELECT count(DISTINCT account_id) FROM qa.turn WHERE id = ANY(CAST(:turns AS uuid[]))")
                .param("turns", ids(turnIds)).query(Long.class).single().intValue();
    }

    /**
     * The keywords of a cluster, as people wrote them (lower case), most askers first. A word is shown only when at least
     * {@code minAskers} distinct accounts among these turns typed exactly that word (k-anonymity on the shown string).
     * Words of one Turkish stem ("bağlantı", "bağlantısı") count as one keyword, shown by their most used form; a word
     * without a stem (a stop word: "neden", "nasıl") is left out, as are numbers and words shorter than 3 letters.
     * Tokens are split on spaces, ASCII punctuation and digits only, so Turkish letters stay inside their word whatever
     * the database locale.
     */
    public List<String> keywords(Collection<UUID> turnIds, int minAskers, int limit) {
        if (turnIds.isEmpty() || limit < 1) return List.of();
        return db.jdbc().sql("""
                        WITH words AS (
                            SELECT t.account_id, w.token FROM qa.turn t
                            CROSS JOIN LATERAL regexp_split_to_table(lower(t.question), '[[:space:][:punct:][:digit:]]+')
                                AS w(token)
                            WHERE t.id = ANY(CAST(:turns AS uuid[])) AND char_length(w.token) >= 3),
                        tokens AS (
                            SELECT token, count(DISTINCT account_id) AS askers, count(*) AS uses,
                                   array_to_string(tsvector_to_array(to_tsvector('turkish', token)), ' ') AS stem
                            FROM words GROUP BY token HAVING count(DISTINCT account_id) >= :k),
                        best AS (
                            SELECT DISTINCT ON (stem) token, askers, uses FROM tokens WHERE stem <> ''
                            ORDER BY stem, askers DESC, uses DESC, token)
                        SELECT token FROM best ORDER BY askers DESC, uses DESC, token LIMIT :limit
                        """)
                .param("turns", ids(turnIds)).param("k", minAskers).param("limit", limit).query(String.class).list();
    }

    /** Normalised question hashes among these turns that at least {@code minAskers} distinct accounts asked. */
    public List<byte[]> representativeHashes(Collection<UUID> turnIds, int minAskers, int limit) {
        if (turnIds.isEmpty() || limit < 1) return List.of();
        return db.jdbc().sql("SELECT question_hash FROM qa.turn WHERE id = ANY(CAST(:turns AS uuid[])) "
                        + "GROUP BY question_hash HAVING count(DISTINCT account_id) >= :k "
                        + "ORDER BY count(DISTINCT account_id) DESC, count(*) DESC, question_hash LIMIT :limit")
                .param("turns", ids(turnIds)).param("k", minAskers).param("limit", limit)
                .query((rs, n) -> rs.getBytes(1)).list();
    }

    /** One stored question text per hash of the window (the report, under the question-text policy only). */
    public Map<String, String> questionsByHash(List<byte[]> hashes, LocalDate from, LocalDate to) {
        Map<String, String> texts = new LinkedHashMap<>();
        if (hashes.isEmpty()) return texts;
        db.jdbc().sql("SELECT encode(question_hash, 'hex'), min(question) FROM qa.turn "
                        + "WHERE question_hash = ANY(:hashes) AND day BETWEEN :from AND :to GROUP BY question_hash")
                .param("hashes", hashes.toArray(byte[][]::new)).param("from", from).param("to", to)
                .query((rs, n) -> Map.entry(rs.getString(1), rs.getString(2)))
                .list().forEach(e -> texts.put(e.getKey(), e.getValue()));
        return texts;
    }

    // ---------------------------------------------------------------- clusters

    public UUID insertCluster(UUID runId, int position, float[] centroid, String model, int turns, int askers,
                              Integer previousTurns, ClusterTrend trend, List<String> keywords, List<byte[]> hashes) {
        return db.jdbc().sql("INSERT INTO qa.question_cluster (run_id, position, centroid, embedding_model, turns, askers, "
                        + "previous_turns, trend, keywords, representative_hashes) VALUES (:run, :position, "
                        + "CAST(:centroid AS extensions.vector), :model, :turns, :askers, :previous, :trend, :keywords, "
                        + ":hashes) RETURNING id")
                .param("run", runId).param("position", position)
                .param("centroid", KnowledgeCardRepository.vectorLiteral(centroid)).param("model", model)
                .param("turns", turns).param("askers", askers).param("previous", previousTurns)
                .param("trend", trend.name()).param("keywords", keywords.toArray(String[]::new))
                .param("hashes", hashes.toArray(byte[][]::new))
                .query(UUID.class).single();
    }

    /** The clusters of a run, largest first. */
    public List<ClusterRow> clusters(UUID runId) {
        return db.jdbc().sql("SELECT id, position, centroid::text, embedding_model, turns, askers, previous_turns, trend, "
                        + "keywords, representative_hashes FROM qa.question_cluster WHERE run_id = :run ORDER BY position")
                .param("run", runId).query(KnowledgeDiagnosticsRepository::cluster).list();
    }

    /** One cluster by id (a skill draft from it, ADR-0053). */
    public Optional<ClusterRow> cluster(UUID clusterId) {
        return db.jdbc().sql("SELECT id, position, centroid::text, embedding_model, turns, askers, previous_turns, trend, "
                        + "keywords, representative_hashes FROM qa.question_cluster WHERE id = :cluster")
                .param("cluster", clusterId).query(KnowledgeDiagnosticsRepository::cluster).optional();
    }

    /** The latest successful run, whatever its window (the skill suggestions, ADR-0053). */
    public Optional<UUID> latestSucceededRun() {
        return db.jdbc().sql("SELECT id FROM qa.diagnostic_run WHERE status = 'SUCCEEDED' "
                        + "ORDER BY finished_at DESC NULLS LAST, id DESC LIMIT 1")
                .query(UUID.class).optional();
    }

    // ---------------------------------------------------------------- citations, coverage, feedback

    /** Documents cited in the window by at least {@code minAskers} distinct accounts, most cited first. */
    public List<DocumentCitations> citedBy(LocalDate from, LocalDate to, int minAskers, int limit) {
        return db.jdbc().sql("SELECT c.document_id, count(*) AS citations, count(DISTINCT t.account_id) AS askers "
                        + "FROM qa.turn_citation c JOIN qa.turn t ON t.id = c.turn_id WHERE t.day BETWEEN :from AND :to "
                        + "GROUP BY c.document_id HAVING count(DISTINCT t.account_id) >= :k "
                        + "ORDER BY citations DESC, c.document_id LIMIT :limit")
                .param("from", from).param("to", to).param("k", minAskers).param("limit", limit)
                .query((rs, n) -> new DocumentCitations(rs.getObject(1, UUID.class), rs.getLong(2), rs.getInt(3)))
                .list();
    }

    /** Per collection (by the given document to collection map): documents cited in the window, distinct accounts. */
    public List<CollectionUse> collectionUse(LocalDate from, LocalDate to, Map<UUID, UUID> collectionOf) {
        if (collectionOf.isEmpty()) return List.of();
        return db.jdbc().sql("SELECT m.collection_id, count(DISTINCT c.document_id), count(DISTINCT t.account_id) "
                        + "FROM qa.turn_citation c JOIN qa.turn t ON t.id = c.turn_id "
                        + "JOIN unnest(CAST(:docs AS uuid[]), CAST(:cols AS uuid[])) AS m(document_id, collection_id) "
                        + "ON m.document_id = c.document_id WHERE t.day BETWEEN :from AND :to GROUP BY m.collection_id")
                .param("docs", ids(collectionOf.keySet())).param("cols", ids(collectionOf.values()))
                .param("from", from).param("to", to)
                .query((rs, n) -> new CollectionUse(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3))).list();
    }

    /** Down ratings of turns citing each collection, by reason, only cells of at least {@code minAskers} raters. */
    public List<FeedbackCell> downRatings(LocalDate from, LocalDate to, Map<UUID, UUID> collectionOf, int minAskers) {
        if (collectionOf.isEmpty()) return List.of();
        return db.jdbc().sql("SELECT m.collection_id, coalesce(f.reason, 'NONE'), count(DISTINCT t.id), "
                        + "count(DISTINCT t.account_id) FROM qa.turn_feedback f JOIN qa.turn t ON t.id = f.turn_id "
                        + "JOIN qa.turn_citation c ON c.turn_id = t.id "
                        + "JOIN unnest(CAST(:docs AS uuid[]), CAST(:cols AS uuid[])) AS m(document_id, collection_id) "
                        + "ON m.document_id = c.document_id WHERE f.rating = 'DOWN' AND t.day BETWEEN :from AND :to "
                        + "GROUP BY m.collection_id, coalesce(f.reason, 'NONE') HAVING count(DISTINCT t.account_id) >= :k "
                        + "ORDER BY 3 DESC, 1, 2")
                .param("docs", ids(collectionOf.keySet())).param("cols", ids(collectionOf.values()))
                .param("from", from).param("to", to).param("k", minAskers)
                .query((rs, n) -> new FeedbackCell(rs.getObject(1, UUID.class), rs.getString(2), rs.getLong(3),
                        rs.getInt(4)))
                .list();
    }

    /** STALE verified answer cards citing each of these documents. */
    public Map<UUID, Integer> staleCardsCiting(Collection<UUID> documentIds) {
        Map<UUID, Integer> counts = new LinkedHashMap<>();
        if (documentIds.isEmpty()) return counts;
        db.jdbc().sql("SELECT k.document_id, count(DISTINCT c.id) FROM qa.knowledge_card c "
                        + "JOIN qa.knowledge_card_citation k ON k.card_id = c.id "
                        + "WHERE c.state = 'STALE' AND k.document_id = ANY(CAST(:docs AS uuid[])) GROUP BY k.document_id")
                .param("docs", ids(documentIds))
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getInt(2)))
                .list().forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** VERIFIED cards whose verification ends between now and {@code until}, oldest expiry first. */
    public Map<UUID, Instant> verifiedExpiring(Instant now, Instant until, int limit) {
        Map<UUID, Instant> due = new LinkedHashMap<>();
        db.jdbc().sql("SELECT id, expires_at FROM qa.knowledge_card WHERE state = 'VERIFIED' AND expires_at > :now "
                        + "AND expires_at <= :until ORDER BY expires_at, id LIMIT :limit")
                .param("now", Timestamp.from(now)).param("until", Timestamp.from(until)).param("limit", limit)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getTimestamp(2).toInstant())).list()
                .forEach(e -> due.put(e.getKey(), e.getValue()));
        return due;
    }

    public long countVerifiedExpiringBy(Instant now, Instant until) {
        return db.jdbc().sql("SELECT count(*) FROM qa.knowledge_card WHERE state = 'VERIFIED' AND expires_at > :now "
                        + "AND expires_at <= :until")
                .param("now", Timestamp.from(now)).param("until", Timestamp.from(until)).query(Long.class).single();
    }

    static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    private static KnowledgeHealthRunResponse run(ResultSet rs, int n) throws SQLException {
        Timestamp finished = rs.getTimestamp("finished_at");
        return new KnowledgeHealthRunResponse(rs.getObject("id", UUID.class), rs.getString("run_trigger"),
                rs.getString("status"), rs.getObject("window_from", LocalDate.class),
                rs.getObject("window_to", LocalDate.class), rs.getInt("questions"), rs.getInt("embedded"),
                rs.getInt("clusters"), rs.getInt("contradictions_checked"), rs.getTimestamp("started_at").toInstant(),
                finished == null ? null : finished.toInstant());
    }

    private static ClusterRow cluster(ResultSet rs, int n) throws SQLException {
        int previous = rs.getInt("previous_turns");
        Integer previousTurns = rs.wasNull() ? null : previous;
        Object[] hashes = (Object[]) rs.getArray("representative_hashes").getArray();
        return new ClusterRow(rs.getObject("id", UUID.class), rs.getInt("position"),
                KnowledgeCardRepository.parseVector(rs.getString(3)), rs.getString("embedding_model"), rs.getInt("turns"),
                rs.getInt("askers"), previousTurns, ClusterTrend.valueOf(rs.getString("trend")),
                Arrays.stream((Object[]) rs.getArray("keywords").getArray()).map(String::valueOf).toList(),
                Arrays.stream(hashes).map(h -> (byte[]) h).toList());
    }
}
