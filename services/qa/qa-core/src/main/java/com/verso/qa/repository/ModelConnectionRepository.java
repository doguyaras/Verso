package com.verso.qa.repository;

import com.verso.qa.api.dto.ModelProviderType;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * {@code qa.model_connection} (V8, ADR-0056). Statements only; callers own the transactions. Every change bumps
 * {@code revision} and is a compare-and-set on the revision the admin saw: two admins cannot overwrite each other.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ModelConnectionRepository {

    /** The settings of a connection, without its key. */
    public record Settings(String name, ModelProviderType type, String baseUrl, String region, String deployment,
                           String project, List<String> models, boolean enabled, ModelConnection.Locality locality,
                           String egressHost) {
    }

    private final QaDatabase db;

    public ModelConnectionRepository(QaDatabase db) {
        this.db = db;
    }

    public void insert(UUID id, Settings s, String createdBy, Instant now) {
        db.jdbc().sql("""
                        INSERT INTO qa.model_connection (id, name, provider_type, base_url, region, deployment, project,
                            models, enabled, locality, egress_host, created_by, created_at, updated_at)
                        VALUES (:id, :name, :type, :baseUrl, :region, :deployment, :project, :models, :enabled,
                                :locality, :host, :by, :now, :now)""")
                .param("id", id).param("name", s.name()).param("type", s.type().name()).param("baseUrl", s.baseUrl())
                .param("region", s.region()).param("deployment", s.deployment()).param("project", s.project())
                .param("models", s.models().toArray(String[]::new)).param("enabled", s.enabled())
                .param("locality", s.locality().name()).param("host", s.egressHost()).param("by", createdBy)
                .param("now", Timestamp.from(now)).update();
    }

    /** True when the row still had {@code revision}. */
    public boolean updateSettings(UUID id, long revision, Settings s, Instant now) {
        return db.jdbc().sql("""
                        UPDATE qa.model_connection SET name = :name, provider_type = :type, base_url = :baseUrl,
                            region = :region, deployment = :deployment, project = :project, models = :models,
                            enabled = :enabled, locality = :locality, egress_host = :host, updated_at = :now,
                            revision = revision + 1
                        WHERE id = :id AND revision = :revision""")
                .param("name", s.name()).param("type", s.type().name()).param("baseUrl", s.baseUrl())
                .param("region", s.region()).param("deployment", s.deployment()).param("project", s.project())
                .param("models", s.models().toArray(String[]::new)).param("enabled", s.enabled())
                .param("locality", s.locality().name()).param("host", s.egressHost()).param("now", Timestamp.from(now))
                .param("id", id).param("revision", revision).update() == 1;
    }

    /** Stores a new sealed key as {@code version}; true when the row still had {@code revision}. */
    public boolean updateKey(UUID id, long revision, SealedKey key, String hint, int version, Instant now) {
        return db.jdbc().sql("""
                        UPDATE qa.model_connection SET key_ciphertext = :ciphertext, key_wrapped_dek = :dek,
                            key_kek_id = :kek, key_hint = :hint, key_version = :version, key_rotated_at = :now,
                            updated_at = :now, revision = revision + 1
                        WHERE id = :id AND revision = :revision""")
                .param("ciphertext", key.ciphertext()).param("dek", key.wrappedDek()).param("kek", key.kekId())
                .param("hint", hint).param("version", version).param("now", Timestamp.from(now)).param("id", id)
                .param("revision", revision).update() == 1;
    }

    /** The result of a test; does not bump the revision (the clients stay valid). */
    public void recordValidation(UUID id, String status, Instant now) {
        db.jdbc().sql("UPDATE qa.model_connection SET validated_at = :now, validation_status = :status WHERE id = :id")
                .param("now", Timestamp.from(now)).param("status", status).param("id", id).update();
    }

    public boolean delete(UUID id) {
        return db.jdbc().sql("DELETE FROM qa.model_connection WHERE id = :id").param("id", id).update() == 1;
    }

    public Optional<ModelConnection> find(UUID id) {
        return db.jdbc().sql("SELECT * FROM qa.model_connection WHERE id = :id").param("id", id)
                .query(ModelConnectionRepository::row).optional();
    }

    public List<ModelConnection> list() {
        return db.jdbc().sql("SELECT * FROM qa.model_connection ORDER BY lower(name), id")
                .query(ModelConnectionRepository::row).list();
    }

    public boolean nameTaken(String name, UUID except) {
        if (except == null) {
            return db.jdbc().sql("SELECT count(*) FROM qa.model_connection WHERE lower(name) = lower(:name)")
                    .param("name", name).query(Long.class).single() > 0;
        }
        return db.jdbc().sql("SELECT count(*) FROM qa.model_connection WHERE lower(name) = lower(:name) AND id <> :except")
                .param("name", name).param("except", except).query(Long.class).single() > 0;
    }

    static ModelConnection row(ResultSet rs, int n) throws SQLException {
        byte[] ciphertext = rs.getBytes("key_ciphertext");
        SealedKey key = ciphertext == null ? null
                : new SealedKey(ciphertext, rs.getBytes("key_wrapped_dek"), rs.getString("key_kek_id"));
        Array models = rs.getArray("models");
        return new ModelConnection(rs.getObject("id", UUID.class), rs.getString("name"),
                ModelProviderType.valueOf(rs.getString("provider_type")), rs.getString("base_url"),
                rs.getString("region"), rs.getString("deployment"), rs.getString("project"),
                Arrays.asList((String[]) models.getArray()), rs.getBoolean("enabled"),
                ModelConnection.Locality.valueOf(rs.getString("locality")), rs.getString("egress_host"), key,
                rs.getString("key_hint"), rs.getInt("key_version"), instant(rs, "key_rotated_at"),
                instant(rs, "validated_at"), rs.getString("validation_status"), instant(rs, "created_at"),
                instant(rs, "updated_at"), rs.getLong("revision"));
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
