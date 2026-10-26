package com.verso.qa.repository;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * Contradiction candidates (ADR-0052): pairs of chunks of different shared documents, by id, page and hash. The values
 * that differ are never stored; the report reads both chunks again with the admin's principals.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ContradictionRepository {

    /** One side of a pair as stored. */
    public record Side(UUID chunkId, UUID documentId, int page, String hash) {
    }

    /** A stored candidate. */
    public record Candidate(UUID id, Side first, Side second, double similarity, int differences, Instant firstSeenAt,
                            Instant lastSeenAt) {
    }

    /** The upsert's result: the row id and whether this call created it (a new candidate fires the hook). */
    public record Upserted(UUID id, boolean created) {
    }

    private final QaDatabase db;

    public ContradictionRepository(QaDatabase db) {
        this.db = db;
    }

    /** Stores a pair (ordered by chunk id) or refreshes its last sighting. */
    public Upserted upsert(Side a, Side b, double similarity, int differences, Instant now) {
        Side first = a.chunkId().compareTo(b.chunkId()) < 0 ? a : b;
        Side second = first == a ? b : a;
        return db.jdbc().sql("INSERT INTO qa.contradiction_candidate (chunk_a, document_a, page_a, hash_a, chunk_b, "
                        + "document_b, page_b, hash_b, similarity, differences, first_seen_at, last_seen_at) VALUES "
                        + "(:ca, :da, :pa, :ha, :cb, :db, :pb, :hb, :similarity, :differences, :now, :now) "
                        + "ON CONFLICT (chunk_a, chunk_b) DO UPDATE SET last_seen_at = EXCLUDED.last_seen_at, "
                        + "similarity = EXCLUDED.similarity, differences = EXCLUDED.differences "
                        + "RETURNING id, (xmax = 0) AS created")
                .param("ca", first.chunkId()).param("da", first.documentId()).param("pa", first.page())
                .param("ha", first.hash()).param("cb", second.chunkId()).param("db", second.documentId())
                .param("pb", second.page()).param("hb", second.hash())
                .param("similarity", BigDecimal.valueOf(similarity).setScale(4, RoundingMode.HALF_UP))
                .param("differences", differences).param("now", Timestamp.from(now))
                .query((rs, n) -> new Upserted(rs.getObject(1, UUID.class), rs.getBoolean(2))).single();
    }

    /** A changed document: its candidates are gone (the next scan finds what is still true). */
    public int deleteForDocument(UUID documentId) {
        return db.jdbc().sql("DELETE FROM qa.contradiction_candidate WHERE document_a = :doc OR document_b = :doc")
                .param("doc", documentId).update();
    }

    public int purgeUnseen(Instant cutoff, int limit) {
        return db.jdbc().sql("DELETE FROM qa.contradiction_candidate WHERE id IN (SELECT id FROM "
                        + "qa.contradiction_candidate WHERE last_seen_at < :cutoff ORDER BY last_seen_at LIMIT :limit)")
                .param("cutoff", Timestamp.from(cutoff)).param("limit", limit).update();
    }

    /** The most recently seen candidates. */
    public List<Candidate> latest(int limit) {
        return db.jdbc().sql("SELECT * FROM qa.contradiction_candidate ORDER BY last_seen_at DESC, id DESC LIMIT :limit")
                .param("limit", limit).query(ContradictionRepository::candidate).list();
    }

    public long count() {
        return db.jdbc().sql("SELECT count(*) FROM qa.contradiction_candidate").query(Long.class).single();
    }

    public Optional<Candidate> find(UUID id) {
        return db.jdbc().sql("SELECT * FROM qa.contradiction_candidate WHERE id = :id").param("id", id)
                .query(ContradictionRepository::candidate).optional();
    }

    /** Live cards (DRAFT, VERIFIED, DISPUTED) bound to either chunk of a candidate. */
    public List<UUID> liveCardsBoundTo(UUID chunkA, UUID chunkB) {
        return db.jdbc().sql("SELECT DISTINCT s.card_id FROM qa.knowledge_card_source s "
                        + "JOIN qa.knowledge_card c ON c.id = s.card_id WHERE s.chunk_id IN (:a, :b) "
                        + "AND c.state IN ('DRAFT', 'VERIFIED', 'DISPUTED') ORDER BY s.card_id")
                .param("a", chunkA).param("b", chunkB).query(UUID.class).list();
    }

    private static Candidate candidate(ResultSet rs, int n) throws SQLException {
        return new Candidate(rs.getObject("id", UUID.class),
                new Side(rs.getObject("chunk_a", UUID.class), rs.getObject("document_a", UUID.class),
                        rs.getInt("page_a"), rs.getString("hash_a")),
                new Side(rs.getObject("chunk_b", UUID.class), rs.getObject("document_b", UUID.class),
                        rs.getInt("page_b"), rs.getString("hash_b")),
                rs.getBigDecimal("similarity").doubleValue(), rs.getInt("differences"),
                rs.getTimestamp("first_seen_at").toInstant(), rs.getTimestamp("last_seen_at").toInstant());
    }
}
