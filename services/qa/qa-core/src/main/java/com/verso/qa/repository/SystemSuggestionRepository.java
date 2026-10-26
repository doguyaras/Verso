package com.verso.qa.repository;

import com.verso.qa.api.dto.SystemSuggestionResponse;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Repository;

/**
 * qa.system_model_suggestion (V7, ADR-0054): DRAFT relations proposed by the optional local-model pass and their
 * review. Statements only; callers own the transactions.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemSuggestionRepository {

    /** A suggestion to store: two entities of one document, the relation and the evidence chunk. */
    public record NewSuggestion(UUID documentId, UUID chunkId, String chunkSha256, String relation, String sourceType,
                                String sourceKey, String targetType, String targetKey) {
    }

    private final QaDatabase db;

    public SystemSuggestionRepository(QaDatabase db) {
        this.db = db;
    }

    /** Stores the suggestion unless the same one exists for the document (any state); true when stored. */
    public boolean insert(NewSuggestion s, String createdBy, Instant now) {
        return db.jdbc().sql("""
                        INSERT INTO qa.system_model_suggestion (document_id, chunk_id, chunk_sha256, relation,
                            source_type, source_key, target_type, target_key, created_by, created_at)
                        VALUES (:document, :chunk, :sha, :relation, :sourceType, :sourceKey, :targetType, :targetKey,
                                :by, :now)
                        ON CONFLICT ON CONSTRAINT uq_system_model_suggestion DO NOTHING""")
                .param("document", s.documentId()).param("chunk", s.chunkId()).param("sha", s.chunkSha256())
                .param("relation", s.relation()).param("sourceType", s.sourceType()).param("sourceKey", s.sourceKey())
                .param("targetType", s.targetType()).param("targetKey", s.targetKey()).param("by", createdBy)
                .param("now", Timestamp.from(now)).update() == 1;
    }

    /** One state's suggestions, newest first. */
    public List<SystemSuggestionResponse> list(String state, int limit) {
        return db.jdbc().sql("SELECT * FROM qa.system_model_suggestion WHERE state = :state "
                        + "ORDER BY created_at DESC, id DESC LIMIT :limit")
                .param("state", state).param("limit", limit).query(SystemSuggestionRepository::row).list();
    }

    public Optional<SystemSuggestionResponse> find(UUID id) {
        return db.jdbc().sql("SELECT * FROM qa.system_model_suggestion WHERE id = :id").param("id", id)
                .query(SystemSuggestionRepository::row).optional();
    }

    /** DRAFT to ACCEPTED or REJECTED, only from DRAFT (a concurrent review changes nothing); true when changed. */
    public boolean review(UUID id, boolean accepted, String reviewer, Instant now) {
        return db.jdbc().sql("""
                        UPDATE qa.system_model_suggestion SET state = :state, reviewed_by = :by, reviewed_at = :now
                        WHERE id = :id AND state = 'DRAFT'""")
                .param("state", accepted ? "ACCEPTED" : "REJECTED").param("by", reviewer)
                .param("now", Timestamp.from(now)).param("id", id).update() == 1;
    }

    /** A deleted or replaced document takes its suggestions with it. */
    public int deleteForDocument(UUID documentId) {
        return db.jdbc().sql("DELETE FROM qa.system_model_suggestion WHERE document_id = :id")
                .param("id", documentId).update();
    }

    /** Suggestions whose evidence chunk no longer has the stored hash (the document was re-chunked). */
    public int delete(UUID id) {
        return db.jdbc().sql("DELETE FROM qa.system_model_suggestion WHERE id = :id").param("id", id).update();
    }

    private static SystemSuggestionResponse row(ResultSet rs, int n) throws SQLException {
        Timestamp reviewed = rs.getTimestamp("reviewed_at");
        return new SystemSuggestionResponse(rs.getObject("id", UUID.class), rs.getObject("document_id", UUID.class),
                rs.getObject("chunk_id", UUID.class), rs.getString("relation"), rs.getString("source_type"),
                rs.getString("source_key"), rs.getString("target_type"), rs.getString("target_key"),
                rs.getString("state"), rs.getTimestamp("created_at").toInstant(),
                reviewed == null ? null : reviewed.toInstant());
    }

    /** The evidence hash of a stored suggestion, for the re-check against the document's current chunks. */
    public Optional<String> chunkSha256(UUID id) {
        return db.jdbc().sql("SELECT chunk_sha256 FROM qa.system_model_suggestion WHERE id = :id").param("id", id)
                .query(String.class).optional();
    }
}
