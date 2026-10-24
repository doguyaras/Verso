package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.SourceUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Hybrid search over document_chunk (ADR-0011, ADR-0022; llm-rules 4.1/6.1). The account, the embedding model and the
 * READY status are conditions of both candidate lists, the vector list and the full-text list: another account's chunk,
 * or a chunk of another model, is never a candidate. Call inside a transaction: the HNSW scan settings are SET LOCAL.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RetrievalRepository {

    /**
     * The text search configuration of document_chunk.search_vector (V3). The question must be parsed with the same
     * one, or its terms would not match the stored ones.
     */
    static final String TEXT_SEARCH_CONFIG = "turkish";

    /**
     * The question's distinct terms joined with OR. plainto_tsquery and websearch_to_tsquery AND every term: a
     * question's own words ("hangi", "demek") then rule out the passage (ADR-0022: 4 of 140 eval questions matched).
     * Terms are quoted for tsquery input; a term with a backslash cannot come out of the default parser, and is
     * dropped rather than escaped.
     */
    private static final String OR_QUERY = """
            (SELECT string_agg('''' || replace(t.lexeme, '''', '''''') || '''', ' | ')
             FROM unnest(to_tsvector('%s', :question)) t
             WHERE strpos(t.lexeme, chr(92)) = 0)::tsquery""".formatted(TEXT_SEARCH_CONFIG);

    /** One fused passage, with the chunk id the term-share lookup needs. */
    public record Candidate(UUID chunkId, UUID documentId, String fileName, int pageNumber, SourceUnit unit,
                            String content, double similarity) {
    }

    private final JdbcClient jdbc;

    public RetrievalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * With a filter, a plain HNSW scan stops after ef_search candidates and can return fewer rows than asked for
     * (phase 4 db review D4: 2 of 5 for a small account). The iterative scan keeps going until enough rows pass the
     * filter; the relaxed order is re-sorted by distance in the query.
     */
    public void prepareFilteredScan(int candidates) {
        jdbc.sql("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true), "
                        + "set_config('hnsw.ef_search', :ef, true)")
                .param("ef", String.valueOf(Math.max(40, candidates * 2))).query((rs, n) -> 1).single();
    }

    /**
     * Reciprocal Rank Fusion of two candidate lists in one statement (ADR-0022): the {@code vectorK} nearest chunks by
     * cosine distance and the {@code lexicalK} best chunks by ts_rank_cd for the question's terms (OR). A chunk scores
     * Σ 1 / (rrfK + rank) over the lists it is in; ties go to the higher cosine similarity. The cosine similarity of
     * every returned chunk is computed, also of one only the full-text list found: the answerability rule reads it,
     * never the fused score.
     */
    public List<Candidate> hybrid(String accountId, String model, float[] question, String questionText, int vectorK,
                                  int lexicalK, int rrfK, int topK) {
        String vector = IngestionRepository.vectorLiteral(question);
        return jdbc.sql("""
                        WITH vec AS (
                            SELECT v.id, row_number() OVER (ORDER BY v.distance, v.id) AS rank
                            FROM (SELECT c.id, c.embedding <=> CAST(:q AS extensions.vector) AS distance
                                  FROM document.document_chunk c
                                  JOIN document.document d ON d.id = c.document_id
                                  WHERE c.account_id = :account AND c.embedding_model = :model AND d.status = 'READY'
                                  ORDER BY c.embedding <=> CAST(:q AS extensions.vector)
                                  LIMIT :vectorK) v
                        ), lex AS (
                            SELECT l.id, row_number() OVER (ORDER BY l.score DESC, l.id) AS rank
                            FROM (SELECT c.id, ts_rank_cd(c.search_vector, query.terms) AS score
                                  FROM document.document_chunk c
                                  JOIN document.document d ON d.id = c.document_id
                                  CROSS JOIN (SELECT %s AS terms) query
                                  WHERE c.account_id = :account AND c.embedding_model = :model AND d.status = 'READY'
                                    AND c.search_vector @@ query.terms
                                  ORDER BY score DESC, c.id
                                  LIMIT :lexicalK) l
                        ), fused AS (
                            SELECT u.id, sum(1.0 / (:rrfK + u.rank)) AS score
                            FROM (SELECT id, rank FROM vec UNION ALL SELECT id, rank FROM lex) u
                            GROUP BY u.id
                        )
                        SELECT c.id, c.document_id, d.file_name, d.format, c.page_number, c.content,
                               1 - (c.embedding <=> CAST(:q AS extensions.vector)) AS similarity
                        FROM fused f
                        JOIN document.document_chunk c ON c.id = f.id
                        JOIN document.document d ON d.id = c.document_id
                        WHERE c.account_id = :account
                        ORDER BY f.score DESC, similarity DESC, c.id
                        LIMIT :k
                        """.formatted(OR_QUERY))
                .param("q", vector).param("account", accountId).param("model", model).param("question", questionText)
                .param("vectorK", vectorK).param("lexicalK", lexicalK).param("rrfK", rrfK).param("k", topK)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"), rs.getInt("page_number"),
                        DocumentFormat.valueOf(rs.getString("format")).unit(), rs.getString("content"),
                        rs.getDouble("similarity")))
                .list();
    }

    /** Chunks the account can search with this model: the denominator of a term share. From the document rows. */
    public long searchableChunks(String accountId, String model) {
        return jdbc.sql("SELECT COALESCE(sum(chunk_count), 0) FROM document.document "
                        + "WHERE account_id = :account AND status = 'READY' AND embedding_model = :model")
                .param("account", accountId).param("model", model).query(Long.class).single();
    }

    /**
     * For each given chunk, how many of the account's searchable chunks contain the rarest question term that this
     * chunk contains (ADR-0022). Each term is counted only up to {@code cap}: a term found that often is common, and
     * counting it exactly would cost a scan of most of a large account. Chunks that share no term are absent.
     */
    public Map<UUID, Long> rarestSharedTermCounts(String accountId, String model, String questionText, List<UUID> chunkIds,
                                                  long cap) {
        if (chunkIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                        WITH terms AS (
                            SELECT DISTINCT t.lexeme, ('''' || replace(t.lexeme, '''', '''''') || '''')::tsquery AS query
                            FROM unnest(to_tsvector('%s', :question)) t
                            WHERE strpos(t.lexeme, chr(92)) = 0
                        ), shared AS (
                            SELECT c.id, terms.lexeme
                            FROM document.document_chunk c
                            JOIN terms ON c.search_vector @@ terms.query
                            WHERE c.id IN (:ids) AND c.account_id = :account
                        ), frequency AS (
                            SELECT terms.lexeme,
                                   (SELECT count(*) FROM (
                                        SELECT 1 FROM document.document_chunk c
                                        JOIN document.document d ON d.id = c.document_id
                                        WHERE c.account_id = :account AND c.embedding_model = :model
                                          AND d.status = 'READY' AND c.search_vector @@ terms.query
                                        LIMIT :cap) hits) AS chunks
                            FROM terms WHERE terms.lexeme IN (SELECT lexeme FROM shared)
                        )
                        SELECT shared.id, min(frequency.chunks) AS chunks
                        FROM shared JOIN frequency ON frequency.lexeme = shared.lexeme
                        GROUP BY shared.id
                        """.formatted(TEXT_SEARCH_CONFIG))
                .param("question", questionText).param("ids", chunkIds).param("account", accountId)
                .param("model", model).param("cap", cap)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getLong("chunks")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /** True when the account has chunks embedded by another model: comparing them would be meaningless (6.1). */
    public boolean hasChunksOfAnotherModel(String accountId, String model) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document.document_chunk WHERE account_id = :account "
                        + "AND embedding_model <> :model)")
                .param("account", accountId).param("model", model).query(Boolean.class).single();
    }
}
