package com.verso.qa.repository;

import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * The answer memory's tables in the qa schema (ADR-0049). Every write runs inside {@link QaDatabase#inTransaction};
 * state changes are conditional updates (the expected state is part of the WHERE clause), so two instances or two
 * admins cannot both apply a transition. No text of a card is ever logged; {@link CardRow#toString()} prints ids.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeCardRepository {

    private static final String CARD_COLUMNS = "id, question, answer, key_facts, state, stale_reason, "
            + "scope_collections, created_by, cycle, required_verifiers, dispute_count, verified_at, expires_at, "
            + "created_at, updated_at, embedding_model";

    /** One bound chunk of a card (ADR-0049): which page and which content hash. */
    public record Binding(UUID chunkId, SourcePage page, String contentSha256) {
    }

    /** A card candidate of the serving lookup: its id and the cosine similarity of its question to the new one. */
    public record Candidate(UUID cardId, double similarity) {
    }

    /** One card row. */
    public record CardRow(UUID id, String question, String answer, List<String> keyFacts, KnowledgeCardState state,
                          KnowledgeStaleReason staleReason, List<UUID> scopeCollections, String createdBy, int cycle,
                          int requiredVerifiers, int disputeCount, Instant verifiedAt, Instant expiresAt,
                          Instant createdAt, Instant updatedAt, String embeddingModel) {

        @Override
        public String toString() {
            return "CardRow[id=" + id + ", state=" + state + ", cycle=" + cycle + "]";
        }
    }

    /** A new card. */
    public record NewCard(String question, byte[] questionHash, float[] embedding, String embeddingModel, String answer,
                          List<String> keyFacts, List<UUID> scopeCollections, UUID sourceTurnId, String createdBy,
                          int requiredVerifiers, Instant now) {

        @Override
        public String toString() {
            return "NewCard[sourceTurnId=" + sourceTurnId + "]";
        }
    }

    /** A citation of a card's answer. */
    public record CitationRow(int number, UUID documentId, int page, String unit) {
    }

    /** The card a stored turn was answered from, and the cycle it was served in. */
    public record ServedFrom(UUID cardId, int cycle) {
    }

    private final QaDatabase db;

    public KnowledgeCardRepository(QaDatabase db) {
        this.db = db;
    }

    // ---------------------------------------------------------------- writes

    public UUID insert(NewCard card) {
        return db.jdbc().sql("INSERT INTO qa.knowledge_card (question, question_hash, question_embedding, embedding_model, "
                        + "answer, key_facts, state, scope_collections, source_turn_id, created_by, required_verifiers, "
                        + "created_at, updated_at) VALUES (:question, :hash, CAST(:embedding AS extensions.vector), :model, "
                        + ":answer, :facts, 'DRAFT', CAST(:scope AS uuid[]), :turn, :createdBy, :required, :now, :now) RETURNING id")
                .param("question", card.question()).param("hash", card.questionHash())
                .param("embedding", vectorLiteral(card.embedding())).param("model", card.embeddingModel())
                .param("answer", card.answer()).param("facts", card.keyFacts().toArray(String[]::new))
                .param("scope", card.scopeCollections().stream().map(UUID::toString).toArray(String[]::new))
                .param("turn", card.sourceTurnId())
                .param("createdBy", card.createdBy()).param("required", card.requiredVerifiers())
                .param("now", Timestamp.from(card.now()))
                .query(UUID.class).single();
    }

    public void insertCitation(UUID cardId, CitationRow citation) {
        db.jdbc().sql("INSERT INTO qa.knowledge_card_citation (card_id, citation_number, document_id, page, unit) "
                        + "VALUES (:card, :number, :document, :page, :unit)")
                .param("card", cardId).param("number", citation.number()).param("document", citation.documentId())
                .param("page", citation.page()).param("unit", citation.unit()).update();
    }

    public void insertBinding(UUID cardId, Binding binding) {
        db.jdbc().sql("INSERT INTO qa.knowledge_card_source (card_id, chunk_id, document_id, page, content_sha256) "
                        + "VALUES (:card, :chunk, :document, :page, :hash) ON CONFLICT DO NOTHING")
                .param("card", cardId).param("chunk", binding.chunkId()).param("document", binding.page().documentId())
                .param("page", binding.page().pageNumber()).param("hash", binding.contentSha256()).update();
    }

    public void insertEvent(UUID cardId, KnowledgeCardState from, KnowledgeCardState to, String reason, Instant at) {
        db.jdbc().sql("INSERT INTO qa.knowledge_card_event (card_id, from_state, to_state, reason, created_at) "
                        + "VALUES (:card, :from, :to, :reason, :at)")
                .param("card", cardId).param("from", from == null ? null : from.name()).param("to", to.name())
                .param("reason", reason).param("at", Timestamp.from(at)).update();
    }

    /** False when this account already verified the card in this cycle. */
    public boolean insertVerification(UUID cardId, int cycle, String verifierId, Instant at) {
        return db.jdbc().sql("INSERT INTO qa.knowledge_card_verification (card_id, cycle, verifier_id, verified_at) "
                        + "VALUES (:card, :cycle, :verifier, :at) ON CONFLICT DO NOTHING")
                .param("card", cardId).param("cycle", cycle).param("verifier", verifierId)
                .param("at", Timestamp.from(at)).update() == 1;
    }

    public int countVerifiers(UUID cardId, int cycle) {
        return db.jdbc().sql("SELECT count(*) FROM qa.knowledge_card_verification WHERE card_id = :card AND cycle = :cycle")
                .param("card", cardId).param("cycle", cycle).query(Integer.class).single();
    }

    public boolean verifiedBy(UUID cardId, int cycle, String verifierId) {
        return db.jdbc().sql("SELECT count(*) FROM qa.knowledge_card_verification "
                        + "WHERE card_id = :card AND cycle = :cycle AND verifier_id = :verifier")
                .param("card", cardId).param("cycle", cycle).param("verifier", verifierId).query(Integer.class).single() > 0;
    }

    /** DRAFT → VERIFIED, with the verification time and the expiry; false when the card was not DRAFT any more. */
    public boolean markVerified(UUID cardId, Instant now, Instant expiresAt) {
        return db.jdbc().sql("UPDATE qa.knowledge_card SET state = 'VERIFIED', stale_reason = NULL, verified_at = :now, "
                        + "expires_at = :expires, updated_at = :now WHERE id = :card AND state = 'DRAFT'")
                .param("card", cardId).param("now", Timestamp.from(now)).param("expires", Timestamp.from(expiresAt))
                .update() == 1;
    }

    /**
     * Starts a new verification cycle: back to DRAFT, disputes of the old cycle no longer count. Only from the state
     * the caller read under the row lock.
     */
    public boolean startCycle(UUID cardId, KnowledgeCardState from, Instant now) {
        return db.jdbc().sql("UPDATE qa.knowledge_card SET state = 'DRAFT', stale_reason = NULL, cycle = cycle + 1, "
                        + "dispute_count = 0, updated_at = :now WHERE id = :card AND state = :from")
                .param("card", cardId).param("from", from.name()).param("now", Timestamp.from(now)).update() == 1;
    }

    /** DRAFT, VERIFIED or DISPUTED → STALE; false when it was already stale or archived (idempotent). */
    public Optional<KnowledgeCardState> markStale(UUID cardId, KnowledgeStaleReason reason, Instant now) {
        return db.jdbc().sql("UPDATE qa.knowledge_card c SET state = 'STALE', stale_reason = :reason, updated_at = :now "
                        + "FROM (SELECT id, state FROM qa.knowledge_card WHERE id = :card FOR UPDATE) old "
                        + "WHERE c.id = old.id AND old.state IN ('DRAFT', 'VERIFIED', 'DISPUTED') RETURNING old.state")
                .param("card", cardId).param("reason", reason.name()).param("now", Timestamp.from(now))
                .query((rs, n) -> KnowledgeCardState.valueOf(rs.getString(1))).optional();
    }

    public boolean markDisputed(UUID cardId, Instant now) {
        return db.jdbc().sql("UPDATE qa.knowledge_card SET state = 'DISPUTED', updated_at = :now "
                        + "WHERE id = :card AND state = 'VERIFIED'")
                .param("card", cardId).param("now", Timestamp.from(now)).update() == 1;
    }

    public Optional<KnowledgeCardState> archive(UUID cardId, Instant now) {
        return db.jdbc().sql("UPDATE qa.knowledge_card c SET state = 'ARCHIVED', stale_reason = NULL, updated_at = :now "
                        + "FROM (SELECT id, state FROM qa.knowledge_card WHERE id = :card FOR UPDATE) old "
                        + "WHERE c.id = old.id AND old.state <> 'ARCHIVED' RETURNING old.state")
                .param("card", cardId).param("now", Timestamp.from(now))
                .query((rs, n) -> KnowledgeCardState.valueOf(rs.getString(1))).optional();
    }

    /** One dispute per pseudonymous account, card and cycle; true when it is new (and then counted). */
    public boolean insertDispute(UUID cardId, int cycle, byte[] accountHash, String reason, LocalDate day, Instant now) {
        boolean added = db.jdbc().sql("INSERT INTO qa.knowledge_card_dispute (card_id, cycle, account_hash, reason, day, "
                        + "created_at) VALUES (:card, :cycle, :hash, :reason, :day, :now) ON CONFLICT DO NOTHING")
                .param("card", cardId).param("cycle", cycle).param("hash", accountHash).param("reason", reason)
                .param("day", day).param("now", Timestamp.from(now)).update() == 1;
        if (added) {
            db.jdbc().sql("UPDATE qa.knowledge_card SET dispute_count = dispute_count + 1, updated_at = :now "
                            + "WHERE id = :card AND cycle = :cycle")
                    .param("card", cardId).param("cycle", cycle).param("now", Timestamp.from(now)).update();
        }
        return added;
    }

    /** Links a stored turn to the card that answered it, in the card's current cycle (same transaction as the turn). */
    public void linkTurn(UUID turnId, UUID cardId) {
        db.jdbc().sql("INSERT INTO qa.turn_knowledge_card (turn_id, card_id, cycle) "
                        + "SELECT :turn, id, cycle FROM qa.knowledge_card WHERE id = :card")
                .param("turn", turnId).param("card", cardId).update();
    }

    /** Exports a verified card into the golden set, or re-activates its case. */
    public void exportGolden(UUID cardId, Instant now) {
        db.jdbc().sql("INSERT INTO qa.golden_case (card_id, active, exported_at) VALUES (:card, true, :now) "
                        + "ON CONFLICT (card_id) DO UPDATE SET active = true, retired_at = NULL")
                .param("card", cardId).param("now", Timestamp.from(now)).update();
    }

    public void retireGolden(UUID cardId, Instant now) {
        db.jdbc().sql("UPDATE qa.golden_case SET active = false, retired_at = :now WHERE card_id = :card AND active")
                .param("card", cardId).param("now", Timestamp.from(now)).update();
    }

    /** Up to {@code limit} VERIFIED cards whose verification ran out, locked for the caller's transaction. */
    public List<UUID> expiredVerified(Instant now, int limit) {
        return db.jdbc().sql("SELECT id FROM qa.knowledge_card WHERE state = 'VERIFIED' AND expires_at <= :now "
                        + "ORDER BY expires_at LIMIT :limit FOR UPDATE SKIP LOCKED")
                .param("now", Timestamp.from(now)).param("limit", limit).query(UUID.class).list();
    }

    /** Archived cards unchanged since the cutoff (with everything that cascades from them). */
    public int purgeArchived(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.knowledge_card WHERE id IN (SELECT id FROM qa.knowledge_card "
                        + "WHERE state = 'ARCHIVED' AND updated_at < :cutoff LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    /** KVKK erasure (ADR-0034): the disputes of one account, found by recomputing the per-card pseudonym. */
    public int deleteDisputesOf(String accountId) {
        return db.jdbc().sql("DELETE FROM qa.knowledge_card_dispute d USING qa.knowledge_card c WHERE d.card_id = c.id "
                        + "AND d.account_hash = sha256(convert_to('verso:dispute:' || c.id::text || ':' || :account, 'UTF8'))")
                .param("account", accountId).update();
    }

    // ---------------------------------------------------------------- reads

    public Optional<CardRow> find(UUID cardId) {
        return db.jdbc().sql("SELECT " + CARD_COLUMNS + " FROM qa.knowledge_card WHERE id = :card")
                .param("card", cardId).query(KnowledgeCardRepository::card).optional();
    }

    public Optional<CardRow> lock(UUID cardId) {
        return db.jdbc().sql("SELECT " + CARD_COLUMNS + " FROM qa.knowledge_card WHERE id = :card FOR UPDATE")
                .param("card", cardId).query(KnowledgeCardRepository::card).optional();
    }

    public List<CardRow> list(KnowledgeCardState state, int page, int size) {
        return db.jdbc().sql("SELECT " + CARD_COLUMNS + " FROM qa.knowledge_card "
                        + "WHERE (CAST(:state AS text) IS NULL OR state = :state) "
                        + "ORDER BY updated_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("state", state == null ? null : state.name()).param("size", size)
                .param("offset", (long) page * size).query(KnowledgeCardRepository::card).list();
    }

    public long count(KnowledgeCardState state) {
        return db.jdbc().sql("SELECT count(*) FROM qa.knowledge_card WHERE (CAST(:state AS text) IS NULL OR state = :state)")
                .param("state", state == null ? null : state.name()).query(Long.class).single();
    }

    /** Cards per state, for the gauges. */
    public List<Object[]> countsByState() {
        return db.jdbc().sql("SELECT state, count(*) FROM qa.knowledge_card GROUP BY state")
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getLong(2)}).list();
    }

    /**
     * The serving lookup (ADR-0049): the {@code limit} VERIFIED, unexpired cards of the current embedding model whose
     * question is nearest to the new one, exact cosine order, with their similarity.
     */
    public List<Candidate> nearestVerified(float[] question, String model, Instant now, int limit) {
        String vector = vectorLiteral(question);
        return db.jdbc().sql("SELECT id, 1 - (question_embedding <=> CAST(:q AS extensions.vector)) AS similarity "
                        + "FROM qa.knowledge_card WHERE state = 'VERIFIED' AND expires_at > :now AND embedding_model = :model "
                        + "ORDER BY question_embedding <=> CAST(:q AS extensions.vector), id LIMIT :limit")
                .param("q", vector).param("now", Timestamp.from(now)).param("model", model).param("limit", limit)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getDouble("similarity"))).list();
    }

    public List<CitationRow> citations(UUID cardId) {
        return db.jdbc().sql("SELECT citation_number, document_id, page, unit FROM qa.knowledge_card_citation "
                        + "WHERE card_id = :card ORDER BY citation_number")
                .param("card", cardId)
                .query((rs, n) -> new CitationRow(rs.getInt(1), rs.getObject(2, UUID.class), rs.getInt(3), rs.getString(4)))
                .list();
    }

    /** The cited documents of several cards (one statement for a page of the admin list). */
    public java.util.Map<UUID, java.util.Set<UUID>> citedDocuments(java.util.Collection<UUID> cardIds) {
        if (cardIds.isEmpty()) return java.util.Map.of();
        java.util.Map<UUID, java.util.Set<UUID>> cited = new java.util.LinkedHashMap<>();
        db.jdbc().sql("SELECT DISTINCT card_id, document_id FROM qa.knowledge_card_citation WHERE card_id IN (:cards)")
                .param("cards", cardIds)
                .query((rs, n) -> java.util.Map.entry(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class))).list()
                .forEach(e -> cited.computeIfAbsent(e.getKey(), k -> new java.util.LinkedHashSet<>()).add(e.getValue()));
        return cited;
    }

    public List<Binding> bindings(UUID cardId) {
        return db.jdbc().sql("SELECT chunk_id, document_id, page, content_sha256 FROM qa.knowledge_card_source "
                        + "WHERE card_id = :card ORDER BY document_id, page, chunk_id")
                .param("card", cardId)
                .query((rs, n) -> new Binding(rs.getObject(1, UUID.class),
                        new SourcePage(rs.getObject(2, UUID.class), rs.getInt(3)), rs.getString(4)))
                .list();
    }

    /** The live cards (not archived, not yet stale) that cite this document. */
    public List<UUID> liveCardsCiting(UUID documentId) {
        return db.jdbc().sql("SELECT DISTINCT c.id FROM qa.knowledge_card c JOIN qa.knowledge_card_citation k "
                        + "ON k.card_id = c.id WHERE k.document_id = :document "
                        + "AND c.state IN ('DRAFT', 'VERIFIED', 'DISPUTED') ORDER BY c.id")
                .param("document", documentId).query(UUID.class).list();
    }

    public Optional<ServedFrom> servedFrom(UUID turnId) {
        return db.jdbc().sql("SELECT card_id, cycle FROM qa.turn_knowledge_card WHERE turn_id = :turn")
                .param("turn", turnId)
                .query((rs, n) -> new ServedFrom(rs.getObject(1, UUID.class), rs.getInt(2))).optional();
    }

    /** Disputes per day of one cycle (counts only: no user-level drill-down, ADR-0048). */
    public List<Object[]> disputesByDay(UUID cardId, int cycle) {
        return db.jdbc().sql("SELECT day, count(*) FROM qa.knowledge_card_dispute WHERE card_id = :card AND cycle = :cycle "
                        + "GROUP BY day ORDER BY day")
                .param("card", cardId).param("cycle", cycle)
                .query((rs, n) -> new Object[] {rs.getObject(1, LocalDate.class), rs.getInt(2)}).list();
    }

    /** The state history, oldest first: {from, to, reason, at}. */
    public List<Object[]> events(UUID cardId) {
        return db.jdbc().sql("SELECT from_state, to_state, reason, created_at FROM qa.knowledge_card_event "
                        + "WHERE card_id = :card ORDER BY created_at, id")
                .param("card", cardId)
                .query((rs, n) -> new Object[] {rs.getString(1), rs.getString(2), rs.getString(3),
                        rs.getTimestamp(4).toInstant()}).list();
    }

    /** The stored question vector of a card (the golden case's query vector). */
    public Optional<float[]> embedding(UUID cardId) {
        return db.jdbc().sql("SELECT question_embedding::text FROM qa.knowledge_card WHERE id = :card")
                .param("card", cardId).query(String.class).optional().map(KnowledgeCardRepository::parseVector);
    }

    static String vectorLiteral(float[] vector) {
        StringBuilder text = new StringBuilder(vector.length * 12).append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) text.append(',');
            float value = vector[i];
            if (!Float.isFinite(value)) throw new IllegalArgumentException("embedding contains a non-finite value");
            text.append(value);
        }
        return text.append(']').toString();
    }

    static float[] parseVector(String text) {
        String body = text.strip();
        body = body.substring(1, body.length() - 1);
        if (body.isBlank()) return new float[0];
        String[] parts = body.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) vector[i] = Float.parseFloat(parts[i].strip());
        return vector;
    }

    private static CardRow card(ResultSet rs, int n) throws SQLException {
        String reason = rs.getString("stale_reason");
        return new CardRow(rs.getObject("id", UUID.class), rs.getString("question"), rs.getString("answer"),
                strings(rs.getArray("key_facts")), KnowledgeCardState.valueOf(rs.getString("state")),
                reason == null ? null : KnowledgeStaleReason.valueOf(reason), uuids(rs.getArray("scope_collections")),
                rs.getString("created_by"), rs.getInt("cycle"), rs.getInt("required_verifiers"),
                rs.getInt("dispute_count"), instant(rs.getTimestamp("verified_at")),
                instant(rs.getTimestamp("expires_at")), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(), rs.getString("embedding_model"));
    }

    private static List<String> strings(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(String::valueOf).toList();
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        return array == null ? List.of() : Arrays.stream((Object[]) array.getArray()).map(o -> (UUID) o).toList();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
