package com.verso.document.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Documents of one account (API side). Every statement carries the account: a document of another account is never
 * read, listed or deleted, it simply does not exist for the caller (IDOR, reference 6.5).
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentRepository {

    static final String COLUMNS = """
            id, account_id, file_name, size_bytes, status, failure_reason, page_count, chunk_count, created_at, updated_at
            """;
    static final RowMapper<DocumentRow> ROW = DocumentRepository::map;

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Inserts the document as PENDING; the id comes from the database (uuidv7, reference 10.3). */
    public DocumentRow insert(String accountId, String fileName, long sizeBytes, UUID idempotencyKey, Instant now) {
        return jdbc.sql("INSERT INTO document.document (account_id, file_name, size_bytes, idempotency_key, "
                        + "next_attempt_at, created_at, updated_at) VALUES (:account, :fileName, :size, :key, :now, :now, :now) "
                        + "RETURNING " + COLUMNS)
                .param("account", accountId).param("fileName", fileName).param("size", sizeBytes)
                .param("key", idempotencyKey).param("now", Timestamp.from(now))
                .query(ROW).single();
    }

    public void insertFile(UUID documentId, byte[] content) {
        jdbc.sql("INSERT INTO document.document_file (document_id, content) VALUES (:id, :content)")
                .param("id", documentId).param("content", content).update();
    }

    public Optional<DocumentRow> findByIdempotencyKey(String accountId, UUID idempotencyKey) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document.document WHERE account_id = :account AND idempotency_key = :key")
                .param("account", accountId).param("key", idempotencyKey).query(ROW).optional();
    }

    public Optional<DocumentRow> find(String accountId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document.document WHERE account_id = :account AND id = :id")
                .param("account", accountId).param("id", id).query(ROW).optional();
    }

    public List<DocumentRow> list(String accountId, int page, int size) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM document.document WHERE account_id = :account "
                        + "ORDER BY created_at DESC, id DESC LIMIT :size OFFSET :offset")
                .param("account", accountId).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long count(String accountId) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE account_id = :account")
                .param("account", accountId).query(Long.class).single();
    }

    /** Removes the document; file, pages, chunks and vectors go with it (ON DELETE CASCADE, llm-rules 4.2). */
    public boolean delete(String accountId, UUID id) {
        return jdbc.sql("DELETE FROM document.document WHERE account_id = :account AND id = :id")
                .param("account", accountId).param("id", id).update() == 1;
    }

    private static DocumentRow map(ResultSet rs, int rowNum) throws SQLException {
        String failure = rs.getString("failure_reason");
        return new DocumentRow(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                rs.getString("file_name"),
                rs.getLong("size_bytes"),
                DocumentStatus.valueOf(rs.getString("status")),
                failure == null ? null : DocumentFailureReason.valueOf(failure),
                (Integer) rs.getObject("page_count"),
                (Integer) rs.getObject("chunk_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
