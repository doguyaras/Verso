package com.verso.document.repository;

import com.verso.document.api.dto.SubjectDocument;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL of KVKK data-subject requests in the "document" schema (ADR-0034). A subject is the uploader
 * ({@code document.account_id}), a reader named directly ({@code user:<sub>}) or the creator of a collection. Reads
 * return metadata only; the only delete is the subject's personal documents, whose derived rows (file, pages, chunks,
 * embeddings, search vectors, readers) go by ON DELETE CASCADE in the same statement (llm-rules 4.2).
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SubjectDocumentRepository {

    private static final String COLUMNS =
            "id, file_name, format, status, size_bytes, collection_id, created_at FROM document.document";

    private final JdbcClient jdbc;

    public SubjectDocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The subject's personal documents ({@code personal}) or those uploaded into collections, oldest first. */
    public List<SubjectDocument> uploadedBy(String sub, boolean personal, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " WHERE account_id = :sub AND collection_id IS "
                        + (personal ? "NULL" : "NOT NULL") + " ORDER BY created_at, id LIMIT :limit")
                .param("sub", sub).param("limit", limit).query(SubjectDocumentRepository::map).list();
    }

    public long countUploadedBy(String sub, boolean personal) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE account_id = :sub AND collection_id IS "
                        + (personal ? "NULL" : "NOT NULL"))
                .param("sub", sub).query(Long.class).single();
    }

    /** Collections granted to the subject by name (ADR-0028 principal {@code user:<sub>}). */
    public List<UUID> grantedCollections(String sub, int limit) {
        return jdbc.sql("SELECT collection_id FROM document.collection_grant WHERE principal = :principal "
                        + "ORDER BY collection_id LIMIT :limit")
                .param("principal", "user:" + sub).param("limit", limit).query(UUID.class).list();
    }

    public List<UUID> createdCollections(String sub, int limit) {
        return jdbc.sql("SELECT id FROM document.collection WHERE created_by = :sub ORDER BY id LIMIT :limit")
                .param("sub", sub).param("limit", limit).query(UUID.class).list();
    }

    /**
     * Serialises with the subject's uploads (DocumentRepository.lockAccount uses the same key) and with a concurrent
     * erasure of the same subject until the transaction ends: the delete then runs after the other one committed and
     * returns only the rows it removed itself, so two erasures never count a document twice.
     */
    public void lockSubject(String sub) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:sub, 0))").param("sub", sub)
                .query((rs, n) -> 1).single();
    }

    public List<UUID> personalIds(String sub, int limit) {
        return jdbc.sql("SELECT id FROM document.document WHERE account_id = :sub AND collection_id IS NULL "
                        + "ORDER BY id LIMIT :limit")
                .param("sub", sub).param("limit", limit).query(UUID.class).list();
    }

    /**
     * Deletes those of {@code ids} that are still personal documents of the subject, with everything derived from them;
     * a document moved into a collection meanwhile is not the subject's to erase any more and stays.
     */
    public List<UUID> deletePersonal(String sub, Collection<UUID> ids) {
        return jdbc.sql("DELETE FROM document.document WHERE account_id = :sub AND collection_id IS NULL "
                        + "AND id = ANY(:ids) RETURNING id")
                .param("sub", sub).param("ids", ids.toArray(UUID[]::new)).query(UUID.class).list();
    }

    private static SubjectDocument map(ResultSet rs, int row) throws SQLException {
        return new SubjectDocument(rs.getObject("id", UUID.class), rs.getString("file_name"),
                DocumentFormat.valueOf(rs.getString("format")), DocumentStatus.valueOf(rs.getString("status")),
                rs.getLong("size_bytes"), rs.getObject("collection_id", UUID.class),
                rs.getTimestamp("created_at").toInstant());
    }
}
