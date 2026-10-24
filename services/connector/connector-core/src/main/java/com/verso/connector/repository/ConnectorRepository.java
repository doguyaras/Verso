package com.verso.connector.repository;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * connector.connector (ADR-0038): settings, schedule and the distributed lock. A sync is claimed with
 * {@code FOR UPDATE SKIP LOCKED}, a lease ({@code locked_until}) and a claim token; renewals and the result are written
 * only by the token's holder (AGENTS.md section 10), so two instances never run the same connector and a stopped
 * instance's lease simply expires. Times come from the database's clock, the same for every instance.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConnectorRepository {

    private static final String COLUMNS = """
            id, name, host, port, share, path_prefix, domain, username, credential_ref, collection_id, include_globs,
            exclude_globs, max_file_bytes, schedule_minutes, enabled, deleting, mass_deletion_confirmed, created_by,
            created_at, updated_at, next_run_at, sync_requested_at, locked_until, claim_token, consecutive_failures,
            last_success_at, last_failure_at, last_error_code, type, base_url, deployment, space_keys, label, ancestor_id,
            include_attachments, skip_restricted, page_size, sync_cursor, full_listing_at, site_id, drive_names,
            tenant_id, client_id, auth_method""";

    static final RowMapper<ConnectorRow> ROW = (rs, n) -> new ConnectorRow(
            rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("host"), rs.getInt("port"),
            rs.getString("share"), rs.getString("path_prefix"), rs.getString("domain"), rs.getString("username"),
            rs.getString("credential_ref"), rs.getObject("collection_id", UUID.class), strings(rs, "include_globs"),
            strings(rs, "exclude_globs"), rs.getLong("max_file_bytes"), rs.getInt("schedule_minutes"),
            rs.getBoolean("enabled"), rs.getBoolean("deleting"), rs.getBoolean("mass_deletion_confirmed"),
            rs.getString("created_by"), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "next_run_at"),
            instant(rs, "sync_requested_at"), instant(rs, "locked_until"), rs.getObject("claim_token", UUID.class),
            rs.getInt("consecutive_failures"), instant(rs, "last_success_at"), instant(rs, "last_failure_at"),
            rs.getString("last_error_code"), rs.getString("type"), confluence(rs), instant(rs, "sync_cursor"),
            instant(rs, "full_listing_at"), sharePoint(rs));

    /** The settings an admin gives; validated by the service. */
    public record Settings(String name, String host, int port, String share, String pathPrefix, String domain,
                           String username, String credentialRef, List<String> includeGlobs, List<String> excludeGlobs,
                           long maxFileBytes, int scheduleMinutes, boolean enabled, String type,
                           ConnectorRow.Confluence confluence, ConnectorRow.SharePoint sharePoint) {

        /** SMB and Confluence settings (the shape before ADR-0045). */
        public Settings(String name, String host, int port, String share, String pathPrefix, String domain,
                        String username, String credentialRef, List<String> includeGlobs, List<String> excludeGlobs,
                        long maxFileBytes, int scheduleMinutes, boolean enabled, String type,
                        ConnectorRow.Confluence confluence) {
            this(name, host, port, share, pathPrefix, domain, username, credentialRef, includeGlobs, excludeGlobs,
                    maxFileBytes, scheduleMinutes, enabled, type, confluence, null);
        }

        @Override
        public String toString() {
            return "Settings[scheduleMinutes=" + scheduleMinutes + "]";
        }
    }

    /** The result of a run, written by the claim holder only. */
    public record Finish(Result result, String errorCode, Duration nextRunIn, boolean consumedMassDeletion) {

        /** SUCCESS resets the failure count and moves last_success_at; NEUTRAL (shutdown) changes neither. */
        public enum Result { SUCCESS, FAILURE, NEUTRAL }
    }

    private final ConnectorDatabase db;

    public ConnectorRepository(ConnectorDatabase db) {
        this.db = db;
    }

    /** Serialises connector creation for the installation-wide limit (check-then-act, AGENTS.md section 10). */
    public void lockInstallation() {
        db.jdbc().sql("SELECT pg_advisory_xact_lock(hashtextextended('connector:installation', 0))")
                .query((rs, n) -> 1).single();
    }

    public long count() {
        return db.jdbc().sql("SELECT count(*) FROM connector.connector").query(Long.class).single();
    }

    /** A new connector, due at once: its first sync runs at the next poll. */
    public ConnectorRow insert(Settings s, UUID collectionId, String createdBy, Instant now) {
        return confluence(db.jdbc().sql("INSERT INTO connector.connector (name, host, port, share, path_prefix, domain, username, "
                        + "credential_ref, collection_id, include_globs, exclude_globs, max_file_bytes, schedule_minutes, "
                        + "enabled, created_by, created_at, updated_at, next_run_at, type, base_url, deployment, space_keys, label, "
                        + "ancestor_id, include_attachments, skip_restricted, page_size, site_id, drive_names, tenant_id, "
                        + "client_id, auth_method) VALUES (:name, :host, :port, "
                        + ":share, :prefix, :domain, :username, :ref, :collection, :include, :exclude, :max, :schedule, "
                        + ":enabled, :createdBy, :now, :now, :now, :type, :baseUrl, :deployment, :spaces, :label, :ancestor, "
                        + ":attachments, :skipRestricted, :pageSize, :siteId, :drives, :tenant, :client, :auth) RETURNING "
                        + COLUMNS)
                .param("name", s.name()).param("host", s.host()).param("port", s.port()).param("share", s.share())
                .param("prefix", s.pathPrefix()).param("domain", s.domain()).param("username", s.username())
                .param("ref", s.credentialRef()).param("collection", collectionId)
                .param("include", s.includeGlobs().toArray(String[]::new))
                .param("exclude", s.excludeGlobs().toArray(String[]::new)).param("max", s.maxFileBytes())
                .param("schedule", s.scheduleMinutes()).param("enabled", s.enabled()).param("createdBy", createdBy)
                .param("now", Timestamp.from(now)), s).query(ROW).single();
    }

    /** Replaces the settings of a connector that is not being deleted; the next sync runs at once with them. */
    public Optional<ConnectorRow> update(UUID id, Settings s, Instant now) {
        return confluence(db.jdbc().sql("UPDATE connector.connector SET name = :name, host = :host, port = :port, share = :share, "
                        + "path_prefix = :prefix, domain = :domain, username = :username, credential_ref = :ref, "
                        + "include_globs = :include, exclude_globs = :exclude, max_file_bytes = :max, "
                        + "schedule_minutes = :schedule, enabled = :enabled, updated_at = :now, next_run_at = :now, "
                        + "base_url = :baseUrl, deployment = :deployment, space_keys = :spaces, label = :label, "
                        + "ancestor_id = :ancestor, include_attachments = :attachments, skip_restricted = :skipRestricted, "
                        // New settings may name other spaces or filters: the next run lists everything again.
                        + "page_size = :pageSize, site_id = :siteId, drive_names = :drives, tenant_id = :tenant, "
                        + "client_id = :client, auth_method = :auth, sync_cursor = NULL, full_listing_at = NULL "
                        + "WHERE id = :id AND NOT deleting AND type = :type RETURNING " + COLUMNS)
                .param("id", id).param("name", s.name()).param("host", s.host()).param("port", s.port())
                .param("share", s.share()).param("prefix", s.pathPrefix()).param("domain", s.domain())
                .param("username", s.username()).param("ref", s.credentialRef())
                .param("include", s.includeGlobs().toArray(String[]::new))
                .param("exclude", s.excludeGlobs().toArray(String[]::new)).param("max", s.maxFileBytes())
                .param("schedule", s.scheduleMinutes()).param("enabled", s.enabled()).param("now", Timestamp.from(now)), s)
                .query(ROW).optional();
    }

    public Optional<ConnectorRow> find(UUID id) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector WHERE id = :id").param("id", id)
                .query(ROW).optional();
    }

    /** By name, newest first on ties (admin list). */
    public List<ConnectorRow> list(int page, int size) {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector ORDER BY lower(name), id "
                        + "LIMIT :size OFFSET :offset")
                .param("size", size).param("offset", (long) page * size).query(ROW).list();
    }

    /** Every connector (metrics snapshot; the installation holds at most max-connectors). */
    public List<ConnectorRow> all() {
        return db.jdbc().sql("SELECT " + COLUMNS + " FROM connector.connector ORDER BY id").query(ROW).list();
    }

    /**
     * Marks the connector for deletion and pauses it; the worker removes its documents and then the row. Idempotent:
     * true while the connector exists (being deleted or not).
     */
    public boolean markDeleting(UUID id, Instant now) {
        return db.jdbc().sql("UPDATE connector.connector SET deleting = true, enabled = false, next_run_at = :now, "
                        + "updated_at = :now WHERE id = :id")
                .param("id", id).param("now", Timestamp.from(now)).update() == 1;
    }

    /** Asks for a run now; a running sync finishes first and then runs again (sync_requested_at). */
    public boolean requestSync(UUID id, boolean confirmMassDeletion) {
        return db.jdbc().sql("UPDATE connector.connector SET next_run_at = now(), sync_requested_at = now(), "
                        + "mass_deletion_confirmed = mass_deletion_confirmed OR :confirm WHERE id = :id AND NOT deleting")
                .param("id", id).param("confirm", confirmMassDeletion).update() == 1;
    }

    /**
     * Claims the most overdue connector whose lease is free: deletions first, then by due time. One statement, so two
     * instances cannot claim the same row (FOR UPDATE SKIP LOCKED; the second skips it or finds the lease taken).
     */
    public Optional<ConnectorRow> claimDue(UUID token, Duration lease) {
        return db.jdbc().sql("UPDATE connector.connector c SET locked_until = now() + make_interval(secs => :lease), "
                        + "claim_token = :token WHERE c.id = (SELECT id FROM connector.connector "
                        + "WHERE (enabled OR deleting) AND next_run_at <= now() "
                        + "AND (locked_until IS NULL OR locked_until < now()) "
                        + "ORDER BY deleting DESC, next_run_at, id LIMIT 1 FOR UPDATE SKIP LOCKED) RETURNING " + COLUMNS)
                .param("token", token).param("lease", (double) lease.toSeconds()).query(ROW).optional();
    }

    /** Extends the lease; false when the claim was lost (expired and taken by another instance). */
    public boolean renew(UUID id, UUID token, Duration lease) {
        return db.jdbc().sql("UPDATE connector.connector SET locked_until = now() + make_interval(secs => :lease) "
                        + "WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", token).param("lease", (double) lease.toSeconds()).update() == 1;
    }

    /**
     * Writes a run's result and frees the lease, only for the claim holder. A sync requested while this run was going
     * makes the next run due at once instead of after the computed delay.
     */
    public boolean finish(UUID id, UUID token, Instant runStartedAt, Finish f) {
        return db.jdbc().sql("UPDATE connector.connector SET locked_until = NULL, claim_token = NULL, "
                        + "next_run_at = CASE WHEN sync_requested_at > :started THEN now() "
                        + "ELSE now() + make_interval(secs => :next) END, "
                        + "consecutive_failures = CASE :result WHEN 'SUCCESS' THEN 0 WHEN 'FAILURE' "
                        + "THEN consecutive_failures + 1 ELSE consecutive_failures END, "
                        + "last_success_at = CASE WHEN :result = 'SUCCESS' THEN now() ELSE last_success_at END, "
                        + "last_failure_at = CASE WHEN :result = 'FAILURE' THEN now() ELSE last_failure_at END, "
                        + "last_error_code = :error, "
                        + "mass_deletion_confirmed = mass_deletion_confirmed AND NOT :consumed "
                        + "WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", token).param("started", Timestamp.from(runStartedAt))
                .param("next", (double) f.nextRunIn().toSeconds()).param("result", f.result().name())
                .param("error", f.errorCode()).param("consumed", f.consumedMassDeletion()).update() == 1;
    }

    /**
     * Confluence and SharePoint: moves the incremental cursor and records a complete full listing, in the finishing transaction and
     * only for the claim holder (called before {@link #finish}, which releases the claim). Null keeps a value.
     */
    public void advance(UUID id, UUID token, Instant cursor, Instant fullListingAt) {
        if (cursor == null && fullListingAt == null) return;
        db.jdbc().sql("UPDATE connector.connector SET sync_cursor = coalesce(:cursor, sync_cursor), "
                        + "full_listing_at = coalesce(:full, full_listing_at) WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", token)
                .param("cursor", cursor == null ? null : Timestamp.from(cursor), Types.TIMESTAMP)
                .param("full", fullListingAt == null ? null : Timestamp.from(fullListingAt), Types.TIMESTAMP)
                .update();
    }

    /** Frees the lease without a result (shutdown): another instance may resume at once. */
    public void release(UUID id, UUID token) {
        db.jdbc().sql("UPDATE connector.connector SET locked_until = NULL, claim_token = NULL "
                        + "WHERE id = :id AND claim_token = :token")
                .param("id", id).param("token", token).update();
    }

    /** Removes a connector whose documents are gone; its items and runs go with it (ON DELETE CASCADE). */
    public boolean delete(UUID id, UUID token) {
        return db.jdbc().sql("DELETE FROM connector.connector WHERE id = :id AND claim_token = :token AND deleting")
                .param("id", id).param("token", token).update() == 1;
    }

    /**
     * The type and the Confluence and SharePoint columns of an insert or update; the other types get the column
     * defaults. base_url and skip_restricted are shared: the Confluence site or the SharePoint site URL, and the v1
     * safety rule of either (restricted pages, files with unique permissions).
     */
    private static JdbcClient.StatementSpec confluence(JdbcClient.StatementSpec spec, Settings s) {
        ConnectorRow.Confluence c = s.confluence();
        ConnectorRow.SharePoint p = s.sharePoint();
        String baseUrl = c != null ? c.baseUrl() : p != null ? p.siteUrl() : null;
        boolean skipRestricted = c != null ? c.skipRestricted() : p == null || p.skipUnique();
        return spec.param("type", s.type())
                .param("baseUrl", baseUrl, Types.VARCHAR)
                .param("deployment", c == null ? null : c.deployment(), Types.VARCHAR)
                .param("spaces", c == null ? new String[0] : c.spaceKeys().toArray(String[]::new))
                .param("label", c == null ? null : c.label(), Types.VARCHAR)
                .param("ancestor", c == null ? null : c.ancestorId(), Types.BIGINT)
                .param("attachments", c != null && c.includeAttachments())
                .param("skipRestricted", skipRestricted)
                .param("pageSize", c == null ? 50 : c.pageSize())
                .param("siteId", p == null ? null : p.siteId(), Types.VARCHAR)
                .param("drives", p == null ? new String[0] : p.drives().toArray(String[]::new))
                .param("tenant", p == null ? null : p.tenantId(), Types.VARCHAR)
                .param("client", p == null ? null : p.clientId(), Types.VARCHAR)
                .param("auth", p == null ? null : p.authMethod(), Types.VARCHAR);
    }

    private static ConnectorRow.SharePoint sharePoint(ResultSet rs) throws SQLException {
        if (!ConnectorRow.SHAREPOINT.equals(rs.getString("type"))) return null;
        return new ConnectorRow.SharePoint(rs.getString("base_url"), rs.getString("site_id"), strings(rs, "drive_names"),
                rs.getString("tenant_id"), rs.getString("client_id"), rs.getString("auth_method"),
                rs.getBoolean("skip_restricted"));
    }

    private static ConnectorRow.Confluence confluence(ResultSet rs) throws SQLException {
        if (!ConnectorRow.CONFLUENCE.equals(rs.getString("type"))) return null;
        long ancestor = rs.getLong("ancestor_id");
        Long ancestorId = rs.wasNull() ? null : ancestor;
        return new ConnectorRow.Confluence(rs.getString("base_url"), rs.getString("deployment"),
                strings(rs, "space_keys"), rs.getString("label"), ancestorId, rs.getBoolean("include_attachments"),
                rs.getBoolean("skip_restricted"), rs.getInt("page_size"));
    }

    private static List<String> strings(ResultSet rs, String column) throws SQLException {
        Array array = rs.getArray(column);
        return array == null ? List.of() : Arrays.asList((String[]) array.getArray());
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp t = rs.getTimestamp(column);
        return t == null ? null : t.toInstant();
    }
}
