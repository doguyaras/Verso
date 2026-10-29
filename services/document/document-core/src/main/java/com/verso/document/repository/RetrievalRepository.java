package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.SourceUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Hybrid search over document_chunk (ADR-0011, ADR-0022, ADR-0028; llm-rules 4.1/6.1). The reader's access, the
 * embedding model and the READY status are conditions of both candidate lists, the vector list and the full-text list,
 * before ranking and before the limit: a chunk of a document outside the reader's principals, or of another model, is
 * never a candidate, never ranked and never counted. Call inside a transaction: the HNSW scan settings are SET LOCAL.
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
     * A chunk's terms for the full-text rank: its context header (ADR-0061, V7; the document title and section, so a
     * question naming a centre by "HÜBAUM" reaches every chunk of that regulation) and its own text. Matching reads
     * both vectors with OR, so each side uses its own GIN index; a chunk indexed before ADR-0061 has no header vector.
     */
    static final String CHUNK_TERMS = "(COALESCE(c.header_vector, ''::tsvector) || c.search_vector)";

    /**
     * Chunk c belongs to a document one of the reader's principals reaches (ADR-0028): one probe of document_principal's
     * primary key per chunk, the same predicate as DocumentRepository.READABLE. Measured against a text[] column with a
     * GIN index on the chunks (docs/evidence/paylasimli-alanlar-dogrulama.md).
     */
    static final String READABLE_CHUNK = "EXISTS (SELECT 1 FROM document.document_principal p "
            + "WHERE p.document_id = c.document_id AND p.principal = ANY(:principals))";

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
                            String content, double similarity, UUID collectionId, String collectionName) {
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
     * cosine distance and the {@code lexicalK} best chunks by ts_rank_cd for the question's terms (OR) over the chunk's
     * text and its context header (ADR-0061), both among the reader's chunks only. A chunk scores Σ 1 / (rrfK + rank) over the lists it is in; ties go to the higher cosine
     * similarity. The cosine similarity of every returned chunk is computed, also of one only the full-text list found:
     * the answerability rule reads it, never the fused score. The final select checks access again (defence in depth).
     */
    public List<Candidate> hybrid(String[] principals, String model, float[] question, String questionText, int vectorK,
                                  int lexicalK, int rrfK, int topK) {
        String vector = IngestionRepository.vectorLiteral(question);
        return jdbc.sql("""
                        WITH vec AS (
                            SELECT v.id, row_number() OVER (ORDER BY v.distance, v.id) AS rank
                            FROM (SELECT c.id, c.embedding <=> CAST(:q AS extensions.vector) AS distance
                                  FROM document.document_chunk c
                                  JOIN document.document d ON d.id = c.document_id
                                  WHERE %1$s AND c.embedding_model = :model AND d.status = 'READY'
                                  ORDER BY c.embedding <=> CAST(:q AS extensions.vector)
                                  LIMIT :vectorK) v
                        ), lex AS (
                            SELECT l.id, row_number() OVER (ORDER BY l.score DESC, l.id) AS rank
                            FROM (SELECT c.id, ts_rank_cd(%3$s, query.terms) AS score
                                  FROM document.document_chunk c
                                  JOIN document.document d ON d.id = c.document_id
                                  CROSS JOIN (SELECT %2$s AS terms) query
                                  WHERE %1$s AND c.embedding_model = :model AND d.status = 'READY'
                                    AND (c.search_vector @@ query.terms OR c.header_vector @@ query.terms)
                                  ORDER BY score DESC, c.id
                                  LIMIT :lexicalK) l
                        ), fused AS (
                            SELECT u.id, sum(1.0 / (:rrfK + u.rank)) AS score
                            FROM (SELECT id, rank FROM vec UNION ALL SELECT id, rank FROM lex) u
                            GROUP BY u.id
                        )
                        SELECT c.id, c.document_id, d.file_name, d.format, c.page_number, c.content,
                               1 - (c.embedding <=> CAST(:q AS extensions.vector)) AS similarity,
                               d.collection_id, col.name AS collection_name
                        FROM fused f
                        JOIN document.document_chunk c ON c.id = f.id
                        JOIN document.document d ON d.id = c.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE %1$s
                        ORDER BY f.score DESC, similarity DESC, c.id
                        LIMIT :k
                        """.formatted(READABLE_CHUNK, OR_QUERY, CHUNK_TERMS))
                .param("q", vector).param("principals", principals).param("model", model)
                .param("question", queryText(questionText))
                .param("vectorK", vectorK).param("lexicalK", lexicalK).param("rrfK", rrfK).param("k", topK)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"), rs.getInt("page_number"),
                        DocumentFormat.valueOf(rs.getString("format")).unit(), rs.getString("content"),
                        rs.getDouble("similarity"), rs.getObject("collection_id", UUID.class),
                        rs.getString("collection_name")))
                .list();
    }

    /**
     * One chunk by id (ADR-0031) when its document is READY and one of the reader's principals reaches it, in the same
     * statement (ADR-0028): an unknown id and an unreadable one both return nothing. Any embedding model: the text is
     * read, not compared.
     */
    public Optional<Candidate> passage(String[] principals, UUID chunkId) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, d.file_name, d.format, c.page_number, c.content,
                               d.collection_id, col.name AS collection_name
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE c.id = :id AND d.status = 'READY' AND %s
                        """.formatted(READABLE_CHUNK))
                .param("id", chunkId).param("principals", principals)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"), rs.getInt("page_number"),
                        DocumentFormat.valueOf(rs.getString("format")).unit(), rs.getString("content"), 0,
                        rs.getObject("collection_id", UUID.class), rs.getString("collection_name")))
                .optional();
    }

    /**
     * The given chunks with their similarity to the question (ADR-0054 enrichment), when their READY documents are
     * readable and indexed with {@code model}; in the order of {@code ids}. Never more than the ids given.
     */
    public List<Candidate> byIds(String[] principals, String model, float[] question, List<UUID> ids) {
        if (ids.isEmpty()) return List.of();
        List<Candidate> found = jdbc.sql("""
                        SELECT c.id, c.document_id, d.file_name, d.format, c.page_number, c.content,
                               1 - (c.embedding <=> CAST(:q AS extensions.vector)) AS similarity,
                               d.collection_id, col.name AS collection_name
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE c.id IN (:ids) AND c.embedding_model = :model AND d.status = 'READY' AND %s
                        """.formatted(READABLE_CHUNK))
                .param("q", IngestionRepository.vectorLiteral(question)).param("ids", ids)
                .param("model", model).param("principals", principals)
                .query((rs, n) -> new Candidate(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getString("file_name"), rs.getInt("page_number"),
                        DocumentFormat.valueOf(rs.getString("format")).unit(), rs.getString("content"),
                        rs.getDouble("similarity"), rs.getObject("collection_id", UUID.class),
                        rs.getString("collection_name")))
                .list();
        return found.stream().sorted(java.util.Comparator.comparingInt(c -> ids.indexOf(c.chunkId()))).toList();
    }

    /** Chunks the reader can search with this model: the denominator of a term share. From the document rows. */
    public long searchableChunks(String[] principals, String model) {
        return jdbc.sql("SELECT COALESCE(sum(d.chunk_count), 0) FROM document.document d "
                        + "WHERE d.status = 'READY' AND d.embedding_model = :model AND " + DocumentRepository.READABLE)
                .param("principals", principals).param("model", model).query(Long.class).single();
    }

    /**
     * For each given chunk, how many of the reader's searchable chunks contain the rarest question term that this
     * chunk contains (ADR-0022). Each term is counted only up to {@code cap}: a term found that often is common, and
     * counting it exactly would cost a scan of most of a large space. Chunks that share no term are absent. Chunks
     * outside the reader's principals are neither looked up nor counted (ADR-0028).
     *
     * <p>The chunk's own text only, not its context header (ADR-0061): the answerability gate's thresholds were chosen
     * on this definition, and a title term every chunk of a document carries would make the gate's lower threshold
     * apply to any question naming the document. The header helps rank candidates; it does not open the gate.
     */
    public Map<UUID, Long> rarestSharedTermCounts(String[] principals, String model, String questionText,
                                                  List<UUID> chunkIds, long cap) {
        if (chunkIds.isEmpty()) return Map.of();
        return jdbc.sql("""
                        WITH terms AS (
                            SELECT DISTINCT t.lexeme, ('''' || replace(t.lexeme, '''', '''''') || '''')::tsquery AS query
                            FROM unnest(to_tsvector('%2$s', :question)) t
                            WHERE strpos(t.lexeme, chr(92)) = 0
                        ), shared AS (
                            SELECT c.id, terms.lexeme
                            FROM document.document_chunk c
                            JOIN terms ON c.search_vector @@ terms.query
                            WHERE c.id IN (:ids) AND %1$s
                        ), frequency AS (
                            SELECT terms.lexeme,
                                   (SELECT count(*) FROM (
                                        SELECT 1 FROM document.document_chunk c
                                        JOIN document.document d ON d.id = c.document_id
                                        WHERE %1$s AND c.embedding_model = :model
                                          AND d.status = 'READY' AND c.search_vector @@ terms.query
                                        LIMIT :cap) hits) AS chunks
                            FROM terms WHERE terms.lexeme IN (SELECT lexeme FROM shared)
                        )
                        SELECT shared.id, min(frequency.chunks) AS chunks
                        FROM shared JOIN frequency ON frequency.lexeme = shared.lexeme
                        GROUP BY shared.id
                        """.formatted(READABLE_CHUNK, TEXT_SEARCH_CONFIG))
                .param("question", queryText(questionText)).param("ids", chunkIds).param("principals", principals)
                .param("model", model).param("cap", cap)
                .query((rs, n) -> Map.entry(rs.getObject("id", UUID.class), rs.getLong("chunks")))
                .list().stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /**
     * PostgreSQL text cannot hold U+0000, which a JSON question may carry: as a parameter it fails the whole search
     * (500). For full-text matching a space is equivalent, it only separates words.
     */
    static String queryText(String question) {
        return question.replace('\u0000', ' ');
    }

    /**
     * True when a document the reader can search has chunks embedded by another model: comparing them would be
     * meaningless (6.1). Another reader's stale document does not block this reader.
     */
    public boolean hasChunksOfAnotherModel(String[] principals, String model) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document.document_principal p "
                        + "JOIN document.document_chunk c ON c.document_id = p.document_id "
                        + "WHERE p.principal = ANY(:principals) AND c.embedding_model <> :model)")
                .param("principals", principals).param("model", model).query(Boolean.class).single();
    }
}
