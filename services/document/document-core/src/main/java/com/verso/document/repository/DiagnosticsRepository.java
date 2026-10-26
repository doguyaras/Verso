package com.verso.document.repository;

import com.verso.document.api.dto.SharedDocument;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.enums.DocumentFormat;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The document side of the company diagnostics (ADR-0052): shared documents and their ages, collection names, and the
 * near-duplicate chunk pairs of the contradiction scan. Shared collections only (collection_id not null): a personal
 * document is never part of a diagnostic. The pair search uses the HNSW index per chunk (LATERAL nearest neighbours).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DiagnosticsRepository {

    private static final String HASH = "encode(sha256(convert_to(%s.content, 'UTF8')), 'hex')";

    /** A chunk pair as read: both chunks and their similarity. */
    public record PairRow(SourceChunk first, SourceChunk second, double similarity) {
    }

    private final JdbcClient jdbc;

    public DiagnosticsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<SharedDocument> sharedDocuments(int limit) {
        return jdbc.sql("SELECT id, collection_id, created_at FROM document.document "
                        + "WHERE status = 'READY' AND collection_id IS NOT NULL ORDER BY created_at, id LIMIT :limit")
                .param("limit", limit)
                .query((rs, n) -> new SharedDocument(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                        rs.getTimestamp(3).toInstant()))
                .list();
    }

    public Map<UUID, String> collectionNames(Collection<UUID> ids) {
        Map<UUID, String> names = new LinkedHashMap<>();
        jdbc.sql("SELECT id, name FROM document.collection WHERE id IN (:ids) ORDER BY id").param("ids", ids)
                .query((rs, n) -> Map.entry(rs.getObject(1, UUID.class), rs.getString(2))).list()
                .forEach(e -> names.put(e.getKey(), e.getValue()));
        return names;
    }

    /** The next chunks of shared READY documents that hold a digit, in id order after {@code after}. */
    public List<UUID> scanSlice(UUID after, int limit) {
        return jdbc.sql("SELECT c.id FROM document.document_chunk c JOIN document.document d ON d.id = c.document_id "
                        + "WHERE d.status = 'READY' AND d.collection_id IS NOT NULL AND c.content ~ '[0-9]' "
                        + "AND (CAST(:after AS uuid) IS NULL OR c.id > CAST(:after AS uuid)) ORDER BY c.id LIMIT :limit")
                .param("after", after).param("limit", limit).query(UUID.class).list();
    }

    /**
     * For each chunk of {@code anchors}, its {@code neighbours} nearest chunks of other shared READY documents of the
     * same embedding model, kept when at least {@code minSimilarity} similar; with text and hashes of both.
     */
    public List<PairRow> nearestOtherDocuments(Collection<UUID> anchors, int neighbours, double minSimilarity) {
        String a = HASH.formatted("a");
        String b = HASH.formatted("b");
        return jdbc.sql("""
                        SELECT a.id AS a_id, a.document_id AS a_document, a.page_number AS a_page, da.format AS a_format,
                               da.collection_id AS a_collection, a.content AS a_content, %s AS a_hash,
                               n.id AS b_id, n.document_id AS b_document, n.page_number AS b_page, n.format AS b_format,
                               n.collection_id AS b_collection, n.content AS b_content, n.hash AS b_hash, n.similarity
                        FROM document.document_chunk a
                        JOIN document.document da ON da.id = a.document_id
                        CROSS JOIN LATERAL (
                            SELECT b.id, b.document_id, b.page_number, db.format, db.collection_id, b.content,
                                   %s AS hash, 1 - (b.embedding <=> a.embedding) AS similarity
                            FROM document.document_chunk b
                            JOIN document.document db ON db.id = b.document_id
                            WHERE db.status = 'READY' AND db.collection_id IS NOT NULL
                              AND b.document_id <> a.document_id AND b.embedding_model = a.embedding_model
                            ORDER BY b.embedding <=> a.embedding
                            LIMIT :neighbours) n
                        WHERE a.id IN (:anchors) AND n.similarity >= :min
                        ORDER BY a.id, n.similarity DESC, n.id
                        """.formatted(a, b))
                .param("anchors", anchors).param("neighbours", neighbours).param("min", minSimilarity)
                .query((rs, n) -> new PairRow(chunk(rs, "a_"), chunk(rs, "b_"), rs.getDouble("similarity")))
                .list();
    }

    private static SourceChunk chunk(ResultSet rs, String prefix) throws SQLException {
        return new SourceChunk(rs.getObject(prefix + "id", UUID.class), rs.getObject(prefix + "document", UUID.class),
                rs.getInt(prefix + "page"), DocumentFormat.valueOf(rs.getString(prefix + "format")).unit(),
                rs.getString(prefix + "hash"), rs.getObject(prefix + "collection", UUID.class), null, null,
                rs.getString(prefix + "content"));
    }
}
