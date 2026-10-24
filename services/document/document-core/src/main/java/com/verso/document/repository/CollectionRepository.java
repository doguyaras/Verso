package com.verso.document.repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Collections and their grants (ADR-0028). A grant change and the document_principal rows it implies are written in
 * the same transaction, under the collection row lock ({@link #lock}): uploads into and moves out of the collection take
 * the same lock, so a document can never keep a revoked principal or miss a new one.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CollectionRepository {

    /** One collection, with its document count and whether the caller's principals match one of its grants. */
    public record CollectionRow(UUID id, String name, String description, long documentCount, boolean readable,
                                Instant createdAt, Instant updatedAt) {
    }

    public record GrantRow(UUID id, String principal, Instant createdAt) {
    }

    public enum LockMode {
        /** Uploads, moves and document deletes: many at once, but no grant change meanwhile. */
        SHARE,
        /** Grant changes, rename and delete of the collection. */
        UPDATE
    }

    private static final String SELECT = """
            SELECT c.id, c.name, c.description, c.created_at, c.updated_at,
                   (SELECT count(*) FROM document.document d WHERE d.collection_id = c.id) AS document_count,
                   EXISTS (SELECT 1 FROM document.collection_grant g
                           WHERE g.collection_id = c.id AND g.principal = ANY(:principals)) AS readable
            FROM document.collection c
            """;
    private static final String READABLE = "EXISTS (SELECT 1 FROM document.collection_grant g "
            + "WHERE g.collection_id = c.id AND g.principal = ANY(:principals))";
    private static final RowMapper<CollectionRow> ROW = CollectionRepository::map;
    private static final RowMapper<GrantRow> GRANT = (rs, n) -> new GrantRow(rs.getObject("id", UUID.class),
            rs.getString("principal"), rs.getTimestamp("created_at").toInstant());

    private final JdbcClient jdbc;

    public CollectionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public CollectionRow insert(String name, String description, String createdBy, Instant now) {
        UUID id = jdbc.sql("INSERT INTO document.collection (name, description, created_by, created_at, updated_at) "
                        + "VALUES (:name, :description, :createdBy, :now, :now) RETURNING id")
                .param("name", name).param("description", description).param("createdBy", createdBy)
                .param("now", Timestamp.from(now)).query(UUID.class).single();
        return find(new String[0], id).orElseThrow();
    }

    public boolean update(UUID id, String name, String description, Instant now) {
        return jdbc.sql("UPDATE document.collection SET name = :name, description = :description, updated_at = :now "
                        + "WHERE id = :id")
                .param("name", name).param("description", description).param("now", Timestamp.from(now)).param("id", id)
                .update() == 1;
    }

    /** Removes an empty collection and its grants; false when it is gone or still holds documents. */
    public boolean deleteIfEmpty(UUID id) {
        return jdbc.sql("DELETE FROM document.collection c WHERE c.id = :id "
                        + "AND NOT EXISTS (SELECT 1 FROM document.document d WHERE d.collection_id = c.id)")
                .param("id", id).update() == 1;
    }

    /** Serialises collection creation until the transaction ends (the installation-wide count, check-then-act). */
    public void lockInstallation() {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended('document.collection', 0))").query((rs, n) -> 1).single();
    }

    /** Takes the collection row lock until the transaction ends; false when the collection does not exist. */
    public boolean lock(UUID id, LockMode mode) {
        String clause = mode == LockMode.UPDATE ? "FOR UPDATE" : "FOR SHARE";
        return jdbc.sql("SELECT id FROM document.collection WHERE id = :id " + clause)
                .param("id", id).query(UUID.class).optional().isPresent();
    }

    /** Whether the collection exists; no reader check (a connector's target, ADR-0038). */
    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document.collection WHERE id = :id)").param("id", id)
                .query(Boolean.class).single();
    }

    public Optional<CollectionRow> find(String[] principals, UUID id) {
        return jdbc.sql(SELECT + " WHERE c.id = :id").param("principals", principals).param("id", id).query(ROW).optional();
    }

    /** The collections the principals may read, by name. */
    public List<CollectionRow> listReadable(String[] principals, int page, int size) {
        return jdbc.sql(SELECT + " WHERE " + READABLE + " ORDER BY lower(c.name), c.id LIMIT :size OFFSET :offset")
                .param("principals", principals).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long countReadable(String[] principals) {
        return jdbc.sql("SELECT count(*) FROM document.collection c WHERE " + READABLE)
                .param("principals", principals).query(Long.class).single();
    }

    /** Every collection (verso-admin manages them all), each with whether the admin may also read it. */
    public List<CollectionRow> listAll(String[] principals, int page, int size) {
        return jdbc.sql(SELECT + " ORDER BY lower(c.name), c.id LIMIT :size OFFSET :offset")
                .param("principals", principals).param("size", size).param("offset", (long) page * size)
                .query(ROW).list();
    }

    public long countAll() {
        return jdbc.sql("SELECT count(*) FROM document.collection").query(Long.class).single();
    }

    public List<GrantRow> grants(UUID collectionId) {
        return jdbc.sql("SELECT id, principal, created_at FROM document.collection_grant "
                        + "WHERE collection_id = :collection ORDER BY created_at, id")
                .param("collection", collectionId).query(GRANT).list();
    }

    public long countGrants(UUID collectionId) {
        return jdbc.sql("SELECT count(*) FROM document.collection_grant WHERE collection_id = :collection")
                .param("collection", collectionId).query(Long.class).single();
    }

    public Optional<GrantRow> findGrant(UUID collectionId, String principal) {
        return jdbc.sql("SELECT id, principal, created_at FROM document.collection_grant "
                        + "WHERE collection_id = :collection AND principal = :principal")
                .param("collection", collectionId).param("principal", principal).query(GRANT).optional();
    }

    /**
     * Adds the grant and the principal to every document of the collection, in the caller's transaction (the caller
     * holds the collection's UPDATE lock).
     */
    public GrantRow grant(UUID collectionId, String principal, String createdBy, Instant now) {
        GrantRow row = jdbc.sql("INSERT INTO document.collection_grant (collection_id, principal, created_by, created_at) "
                        + "VALUES (:collection, :principal, :createdBy, :now) RETURNING id, principal, created_at")
                .param("collection", collectionId).param("principal", principal).param("createdBy", createdBy)
                .param("now", Timestamp.from(now)).query(GRANT).single();
        jdbc.sql("INSERT INTO document.document_principal (document_id, principal) "
                        + "SELECT d.id, :principal FROM document.document d WHERE d.collection_id = :collection "
                        + "ON CONFLICT DO NOTHING")
                .param("collection", collectionId).param("principal", principal).update();
        return row;
    }

    /**
     * Removes the grant and its principal from every document of the collection; empty when the grant is not one of
     * this collection's. The caller holds the collection's UPDATE lock.
     */
    public Optional<String> revoke(UUID collectionId, UUID grantId) {
        Optional<String> principal = jdbc.sql("DELETE FROM document.collection_grant "
                        + "WHERE id = :grant AND collection_id = :collection RETURNING principal")
                .param("grant", grantId).param("collection", collectionId).query(String.class).optional();
        principal.ifPresent(p -> jdbc.sql("DELETE FROM document.document_principal p USING document.document d "
                        + "WHERE p.document_id = d.id AND d.collection_id = :collection AND p.principal = :principal")
                .param("collection", collectionId).param("principal", p).update());
        return principal;
    }

    private static CollectionRow map(ResultSet rs, int rowNum) throws SQLException {
        return new CollectionRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("description"),
                rs.getLong("document_count"), rs.getBoolean("readable"), rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant());
    }
}
