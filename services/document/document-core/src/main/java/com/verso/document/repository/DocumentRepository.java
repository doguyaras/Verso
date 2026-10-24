package com.verso.document.repository;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.enums.DocumentFailureReason;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Documents (API side). Every reader-facing statement carries the reader's principals or the owner's account in the
 * statement itself: a document outside them is never read, listed or deleted, it simply does not exist for the caller
 * (IDOR, reference 6.5; ADR-0028). The collection-wide statements are for verso-admin only; the service checks the role
 * and the controller declares it.
 *
 * <p>document_principal is written by the statement that decides it: the insert (personal: the owner; collection: the
 * collection's grants, read under the collection row lock the service holds) and the move.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentRepository {

    /** A document with the name of its collection (null for a personal one); "d" is the document alias. */
    static final String SELECT = """
            SELECT d.id, d.account_id, d.file_name, d.format, d.size_bytes, d.status, d.failure_reason, d.page_count,
                   d.chunk_count, d.created_at, d.updated_at, d.collection_id, col.name AS collection_name
            FROM document.document d LEFT JOIN document.collection col ON col.id = d.collection_id
            """;
    /**
     * The reader may read document d: one of the reader's principals is one of the document's (ADR-0028). The same
     * predicate as retrieval's (RetrievalRepository.READABLE_CHUNK), so listing and searching never disagree.
     */
    static final String READABLE = "EXISTS (SELECT 1 FROM document.document_principal p "
            + "WHERE p.document_id = d.id AND p.principal = ANY(:principals))";
    static final RowMapper<DocumentRow> ROW = DocumentRepository::map;

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A personal document of the account, PENDING; its only reader is the owner. */
    public DocumentRow insert(String accountId, String fileName, DocumentFormat format, long sizeBytes, UUID idempotencyKey,
                              Instant now) {
        return insert(accountId, null, fileName, format, sizeBytes, idempotencyKey, now);
    }

    /**
     * Inserts the document as PENDING (the id comes from the database, uuidv7, reference 10.3) together with its
     * readers, in one statement: the owner for a personal document ({@code collectionId} null), the collection's grants
     * otherwise. For a collection the caller holds the collection row lock, so no grant change can slip between.
     */
    public DocumentRow insert(String accountId, UUID collectionId, String fileName, DocumentFormat format, long sizeBytes,
                              UUID idempotencyKey, Instant now) {
        return jdbc.sql("""
                        WITH d AS (
                            INSERT INTO document.document (account_id, collection_id, file_name, format, size_bytes,
                                                           idempotency_key, next_attempt_at, created_at, updated_at)
                            VALUES (:account, :collection, :fileName, :format, :size, :key, :now, :now, :now)
                            RETURNING *
                        ), personal AS (
                            INSERT INTO document.document_principal (document_id, principal)
                            SELECT d.id, 'user:' || d.account_id FROM d WHERE d.collection_id IS NULL
                        ), shared AS (
                            INSERT INTO document.document_principal (document_id, principal)
                            SELECT d.id, g.principal FROM d
                            JOIN document.collection_grant g ON g.collection_id = d.collection_id
                        )
                        SELECT d.id, d.account_id, d.file_name, d.format, d.size_bytes, d.status, d.failure_reason,
                               d.page_count, d.chunk_count, d.created_at, d.updated_at, d.collection_id,
                               col.name AS collection_name
                        FROM d LEFT JOIN document.collection col ON col.id = d.collection_id
                        """)
                .param("account", accountId).param("collection", collectionId).param("fileName", fileName)
                .param("format", format.name()).param("size", sizeBytes).param("key", idempotencyKey)
                .param("now", Timestamp.from(now))
                .query(ROW).single();
    }

    public void insertFile(UUID documentId, byte[] content) {
        jdbc.sql("INSERT INTO document.document_file (document_id, content) VALUES (:id, :content)")
                .param("id", documentId).param("content", content).update();
    }

    /**
     * The uploader's earlier document with this key (reference 6.4), only while one of the caller's principals still
     * reaches it: an upload that moved to a collection the caller cannot read is not replayed (its name and the
     * collection's name would leak to a demoted uploader; ADR-0028). verso-admin also replays a collection document it
     * uploaded without holding a grant: it manages those (the same rule as the service's {@code visible}).
     */
    public Optional<DocumentRow> findByIdempotencyKey(String accountId, UUID idempotencyKey, String[] principals,
                                                      boolean admin) {
        return jdbc.sql(SELECT + " WHERE d.account_id = :account AND d.idempotency_key = :key AND ("
                        + READABLE + " OR (:admin AND d.collection_id IS NOT NULL))")
                .param("account", accountId).param("key", idempotencyKey).param("principals", principals)
                .param("admin", admin).query(ROW).optional();
    }

    /**
     * The id of the source's document with this key (ADR-0038): a connector is not a reader, so no principal filter;
     * the key belongs to that source account only.
     */
    public Optional<UUID> findIdByIdempotencyKey(String accountId, UUID idempotencyKey) {
        return jdbc.sql("SELECT id FROM document.document WHERE account_id = :account AND idempotency_key = :key")
                .param("account", accountId).param("key", idempotencyKey).query(UUID.class).optional();
    }

    /** Those of the ids that are documents of the collection (a connector's reconciliation, ADR-0038). */
    public List<UUID> presentInCollection(UUID collectionId, Collection<UUID> ids) {
        return jdbc.sql("SELECT id FROM document.document WHERE collection_id = :collection AND id = ANY(:ids)")
                .param("collection", collectionId).param("ids", ids.toArray(UUID[]::new)).query(UUID.class).list();
    }

    /** A document the account uploaded, wherever it lives (worker and tests; not a reader check). */
    public Optional<DocumentRow> find(String accountId, UUID id) {
        return jdbc.sql(SELECT + " WHERE d.account_id = :account AND d.id = :id")
                .param("account", accountId).param("id", id).query(ROW).optional();
    }

    /** The document when one of the reader's principals reaches it (ADR-0028). */
    public Optional<DocumentRow> findReadable(String[] principals, UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id AND " + READABLE)
                .param("id", id).param("principals", principals).query(ROW).optional();
    }

    /** A collection document, whoever may read it: verso-admin's management view (ADR-0028). */
    public Optional<DocumentRow> findInAnyCollection(UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id AND d.collection_id IS NOT NULL")
                .param("id", id).query(ROW).optional();
    }

    /** The reader's documents, personal and shared, newest first. */
    public List<DocumentRow> listReadable(String[] principals, int page, int size) {
        return jdbc.sql(SELECT + " WHERE " + READABLE + " ORDER BY d.created_at DESC, d.id DESC LIMIT :size OFFSET :offset")
                .param("principals", principals).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long countReadable(String[] principals) {
        return jdbc.sql("SELECT count(*) FROM document.document d WHERE " + READABLE)
                .param("principals", principals).query(Long.class).single();
    }

    /**
     * One collection's documents the reader's principals reach, newest first: the second layer behind the service's
     * collection check for every caller but verso-admin (ADR-0028).
     */
    public List<DocumentRow> listReadableInCollection(String[] principals, UUID collectionId, int page, int size) {
        return jdbc.sql(SELECT + " WHERE d.collection_id = :collection AND " + READABLE
                        + " ORDER BY d.created_at DESC, d.id DESC LIMIT :size OFFSET :offset")
                .param("principals", principals).param("collection", collectionId).param("size", size)
                .param("offset", (long) page * size).query(ROW).list();
    }

    public long countReadableInCollection(String[] principals, UUID collectionId) {
        return jdbc.sql("SELECT count(*) FROM document.document d WHERE d.collection_id = :collection AND " + READABLE)
                .param("principals", principals).param("collection", collectionId).query(Long.class).single();
    }

    /**
     * One collection's documents, newest first, for verso-admin's management view; the service has checked that the
     * caller may see the collection.
     */
    public List<DocumentRow> listInCollection(UUID collectionId, int page, int size) {
        return jdbc.sql(SELECT + " WHERE d.collection_id = :collection "
                        + "ORDER BY d.created_at DESC, d.id DESC LIMIT :size OFFSET :offset")
                .param("collection", collectionId).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long countInCollection(UUID collectionId) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE collection_id = :collection")
                .param("collection", collectionId).query(Long.class).single();
    }

    /** Serialises the uploads of one account until the transaction ends (quota check, reference 4.3 check-then-act). */
    public void lockAccount(String accountId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:account, 0))").param("account", accountId)
                .query((rs, n) -> 1).single();
    }

    /**
     * Serialises the uploads and moves into one collection until the transaction ends: the collection row lock is
     * shared by design (uploads run side by side), so the document count needs its own lock (check-then-act).
     */
    public void lockCollectionQuota(UUID collectionId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('collection:' || :collection, 0))")
                .param("collection", collectionId.toString()).query((rs, n) -> 1).single();
    }

    /** Documents the account uploaded that still wait for or are in ingestion, wherever they live. */
    public long countQueued(String accountId) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE account_id = :account "
                        + "AND status IN ('PENDING', 'PROCESSING')")
                .param("account", accountId).query(Long.class).single();
    }

    /** The account's personal documents: its quota (collection documents count against their collection). */
    public long count(String accountId) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE account_id = :account AND collection_id IS NULL")
                .param("account", accountId).query(Long.class).single();
    }

    /**
     * Raises the lock wait of the current transaction. A deletion may meet the ingestion worker's transaction that
     * stores a large document's chunks (seconds); the role default of 3 s would turn the KVKK erasure into an error
     * (phase 4 review D2).
     */
    public void waitForLocksUpTo(Duration timeout) {
        jdbc.sql("SELECT set_config('lock_timeout', :timeout, true)")
                .param("timeout", timeout.toMillis() + "ms").query(String.class).single();
    }

    /**
     * Removes the account's personal document; file, pages, chunks, vectors and readers go with it (ON DELETE CASCADE,
     * llm-rules 4.2). A collection document is not the owner's to delete (ADR-0028).
     */
    public boolean delete(String accountId, UUID id) {
        return jdbc.sql("DELETE FROM document.document WHERE account_id = :account AND id = :id AND collection_id IS NULL")
                .param("account", accountId).param("id", id).update() == 1;
    }

    /** Removes a document of this collection (verso-admin; the caller holds the collection row lock). */
    public boolean deleteFromCollection(UUID collectionId, UUID id) {
        return jdbc.sql("DELETE FROM document.document WHERE id = :id AND collection_id = :collection")
                .param("collection", collectionId).param("id", id).update() == 1;
    }

    /**
     * Locks the document row against another move or a status change until the transaction ends. FOR NO KEY UPDATE, the
     * lock the UPDATE itself takes: it does not block the key-share lock that a grant change's document_principal
     * insert needs, so a move and a grant change on the same collection cannot deadlock.
     */
    public Optional<DocumentRow> lockForMove(UUID id) {
        return jdbc.sql(SELECT + " WHERE d.id = :id FOR NO KEY UPDATE OF d")
                .param("id", id).query(ROW).optional();
    }

    /**
     * Moves the document and rewrites its readers in the same transaction: the uploader for the personal space, the
     * target collection's grants otherwise. The caller holds the locks of both collections involved.
     */
    public void move(UUID id, UUID collectionId, Instant now) {
        jdbc.sql("UPDATE document.document SET collection_id = :collection, updated_at = :now WHERE id = :id")
                .param("collection", collectionId).param("now", Timestamp.from(now)).param("id", id).update();
        jdbc.sql("DELETE FROM document.document_principal WHERE document_id = :id").param("id", id).update();
        jdbc.sql("""
                        INSERT INTO document.document_principal (document_id, principal)
                        SELECT d.id, 'user:' || d.account_id FROM document.document d
                        WHERE d.id = :id AND d.collection_id IS NULL
                        UNION
                        SELECT d.id, g.principal FROM document.document d
                        JOIN document.collection_grant g ON g.collection_id = d.collection_id
                        WHERE d.id = :id
                        """)
                .param("id", id).update();
    }

    /**
     * What the reader may know about each of {@code ids} (DocumentCatalog): the name and collection only when one of the
     * reader's principals reaches the document, otherwise that it exists. Deleted documents are absent.
     */
    public List<DocumentAccess> access(String[] principals, Collection<UUID> ids) {
        return jdbc.sql("""
                        SELECT d.id, a.readable,
                               CASE WHEN a.readable THEN d.file_name END AS file_name,
                               CASE WHEN a.readable THEN d.collection_id END AS collection_id,
                               CASE WHEN a.readable THEN col.name END AS collection_name
                        FROM document.document d
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        CROSS JOIN LATERAL (SELECT %s AS readable) a
                        WHERE d.id = ANY(:ids)
                        """.formatted(READABLE))
                .param("principals", principals).param("ids", ids.toArray(UUID[]::new))
                .query((rs, n) -> new DocumentAccess(rs.getObject("id", UUID.class), rs.getBoolean("readable"),
                        rs.getString("file_name"), rs.getObject("collection_id", UUID.class),
                        rs.getString("collection_name")))
                .list();
    }

    /** Admin metrics only (ADR-0023): the documents among {@code ids}, whoever owns them. */
    public List<DocumentRow> findAllAcrossAccounts(Collection<UUID> ids) {
        return jdbc.sql(SELECT + " WHERE d.id = ANY(:ids)")
                .param("ids", ids.toArray(UUID[]::new)).query(ROW).list();
    }

    /** Admin metrics only (ADR-0023): READY documents of every account outside {@code excluded}, oldest first. */
    public List<DocumentRow> readyExcept(Collection<UUID> excluded, int limit) {
        return jdbc.sql(SELECT + " WHERE d.status = 'READY' AND NOT (d.id = ANY(:ids)) "
                        + "ORDER BY d.created_at, d.id LIMIT :limit")
                .param("ids", excluded.toArray(UUID[]::new)).param("limit", limit).query(ROW).list();
    }

    public long countReadyExcept(Collection<UUID> excluded) {
        return jdbc.sql("SELECT count(*) FROM document.document WHERE status = 'READY' AND NOT (id = ANY(:ids))")
                .param("ids", excluded.toArray(UUID[]::new)).query(Long.class).single();
    }

    private static DocumentRow map(ResultSet rs, int rowNum) throws SQLException {
        String failure = rs.getString("failure_reason");
        return new DocumentRow(
                rs.getObject("id", UUID.class),
                rs.getString("account_id"),
                rs.getString("file_name"),
                DocumentFormat.valueOf(rs.getString("format")),
                rs.getLong("size_bytes"),
                DocumentStatus.valueOf(rs.getString("status")),
                failure == null ? null : DocumentFailureReason.valueOf(failure),
                (Integer) rs.getObject("page_count"),
                (Integer) rs.getObject("chunk_count"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getObject("collection_id", UUID.class),
                rs.getString("collection_name"));
    }
}
