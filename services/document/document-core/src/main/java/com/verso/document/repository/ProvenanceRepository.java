package com.verso.document.repository;

import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.enums.DocumentFormat;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Chunks of cited pages with their content hashes, and the reader principals of cited documents (ADR-0049). The hash is
 * computed by the database from the stored text (SHA-256 of its UTF-8 bytes), so the binding never depends on how the
 * JVM normalises text. The reader's variant carries the reader's principals in the statement (ADR-0028); the system
 * variant returns no text and no names.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProvenanceRepository {

    private static final String HASH = "encode(sha256(convert_to(c.content, 'UTF8')), 'hex')";

    private final JdbcClient jdbc;

    public ProvenanceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The chunks of READY documents among {@code documentIds} on any of {@code pageNumbers}, readable by the
     * principals: the caller keeps only the exact (document, page) pairs it asked for.
     */
    public List<SourceChunk> readable(String[] principals, Collection<UUID> documentIds, Collection<Integer> pageNumbers) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, c.page_number, d.format, d.file_name, d.collection_id,
                               col.name AS collection_name, c.content, %s AS hash
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE c.document_id IN (:documents) AND c.page_number IN (:pages) AND d.status = 'READY'
                          AND %s
                        ORDER BY c.document_id, c.page_number, c.chunk_index
                        """.formatted(HASH, RetrievalRepository.READABLE_CHUNK))
                .param("documents", documentIds).param("pages", pageNumbers).param("principals", principals)
                .query((rs, n) -> new SourceChunk(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getInt("page_number"), DocumentFormat.valueOf(rs.getString("format")).unit(),
                        rs.getString("hash"), rs.getObject("collection_id", UUID.class), rs.getString("file_name"),
                        rs.getString("collection_name"), rs.getString("content")))
                .list();
    }

    /** The same chunks for the system's staleness sweep: ids and hashes only, any account. */
    public List<SourceChunk> hashes(Collection<UUID> documentIds, Collection<Integer> pageNumbers) {
        return jdbc.sql("""
                        SELECT c.id, c.document_id, c.page_number, d.format, d.collection_id, %s AS hash
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        WHERE c.document_id IN (:documents) AND c.page_number IN (:pages) AND d.status = 'READY'
                        ORDER BY c.document_id, c.page_number, c.chunk_index
                        """.formatted(HASH))
                .param("documents", documentIds).param("pages", pageNumbers)
                .query((rs, n) -> new SourceChunk(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                        rs.getInt("page_number"), DocumentFormat.valueOf(rs.getString("format")).unit(),
                        rs.getString("hash"), rs.getObject("collection_id", UUID.class), null, null, null))
                .list();
    }

    /** The reader principals of the READY documents among {@code documentIds}: who may read at least one of them. */
    public String[] principalsOf(Collection<UUID> documentIds) {
        return jdbc.sql("SELECT DISTINCT p.principal FROM document.document_principal p "
                        + "JOIN document.document d ON d.id = p.document_id "
                        + "WHERE p.document_id IN (:documents) AND d.status = 'READY' ORDER BY p.principal")
                .param("documents", documentIds).query(String.class).list().toArray(String[]::new);
    }
}
