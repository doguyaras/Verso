package com.verso.document.repository;

import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SystemDefinition;
import com.verso.document.api.dto.SystemEntitySummary;
import com.verso.document.api.dto.SystemLink;
import com.verso.document.api.dto.SystemSource;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.document.api.enums.SystemRelationType;
import com.verso.document.systemmodel.ExtractedModel;
import com.verso.document.systemmodel.ExtractedModel.Entity;
import com.verso.document.systemmodel.ExtractedModel.Mention;
import com.verso.document.systemmodel.ExtractedModel.Ref;
import com.verso.document.systemmodel.ExtractedModel.Relation;
import com.verso.document.systemmodel.SystemModelPlan;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The light system model's tables (V6, ADR-0054). Writes replace one document's model as a whole; every read takes the
 * reader's principals and filters on them in the query itself, the same predicate as retrieval (ADR-0028,
 * RetrievalRepository.READABLE_CHUNK), and reads READY documents only. Statements only: the callers own the
 * transactions.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SystemModelRepository {

    static final String HASH = "encode(sha256(convert_to(c.content, 'UTF8')), 'hex')";
    /** Entity e belongs to a READY document one of the reader's principals reaches. */
    static final String READABLE_ENTITY = "EXISTS (SELECT 1 FROM document.document_principal p "
            + "WHERE p.document_id = e.document_id AND p.principal = ANY(:principals))";
    private static final String SOURCE_COLUMNS = """
            c.id AS chunk_id, c.document_id, d.file_name, d.format, c.page_number, %s AS hash, d.collection_id,
            col.name AS collection_name""".formatted(HASH);
    private static final int INSERT_BATCH = 200;

    /** One chunk as the extractor reads it: id, position, text, hash. The text is never logged. */
    public record ChunkRow(UUID id, int chunkIndex, String content, String sha256) {

        @Override
        public String toString() {
            return "ChunkRow[id=" + id + ", chunkIndex=" + chunkIndex + "]";
        }
    }

    /** A signal candidate as read: the entity, its documents and, for terms, its raw definitions. */
    public record SignalRow(SystemEntityType type, String key, String label, List<UUID> documentIds,
                            List<String> values) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    public SystemModelRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    // --- writes

    /** The document's chunks in order, with their SHA-256 computed by the database (like the ADR-0049 binding). */
    public List<ChunkRow> chunks(UUID documentId) {
        return jdbc.sql("SELECT c.id, c.chunk_index, c.content, " + HASH + " AS hash FROM document.document_chunk c "
                        + "WHERE c.document_id = :id ORDER BY c.chunk_index")
                .param("id", documentId)
                .query((rs, n) -> new ChunkRow(rs.getObject("id", UUID.class), rs.getInt("chunk_index"),
                        rs.getString("content"), rs.getString("hash")))
                .list();
    }

    public Optional<SystemModelPlan.Stored> state(UUID documentId) {
        return jdbc.sql("SELECT extractor_version, chunks_sha256 FROM document.system_model_state WHERE document_id = :id")
                .param("id", documentId)
                .query((rs, n) -> new SystemModelPlan.Stored(rs.getInt(1), rs.getString(2)))
                .optional();
    }

    /**
     * Replaces the document's model: the old entities go (their relations and mentions with them, ON DELETE CASCADE),
     * the new ones are written with each mention and relation bound to its chunk id and hash, and the state row records
     * the version and digest. Positions the chunks do not have are skipped.
     */
    public void replace(UUID documentId, ExtractedModel model, List<ChunkRow> chunks, String digest, int version,
                        Instant now) {
        delete(documentId);
        Map<Integer, ChunkRow> byIndex = new HashMap<>();
        for (ChunkRow chunk : chunks) byIndex.put(chunk.chunkIndex(), chunk);
        List<Object[]> entityRows = new ArrayList<>(model.entities().size());
        for (Entity entity : model.entities()) {
            entityRows.add(new Object[]{documentId, entity.ref().type().name(), entity.ref().key(), entity.label(),
                    entity.ordinal()});
        }
        batch("INSERT INTO document.system_entity (document_id, type, key, label, ordinal) VALUES (?, ?, ?, ?, ?)",
                entityRows);
        Map<Ref, UUID> ids = new HashMap<>();
        jdbc.sql("SELECT id, type, key FROM document.system_entity WHERE document_id = :id").param("id", documentId)
                .query((rs, n) -> Map.entry(new Ref(SystemEntityType.valueOf(rs.getString("type")), rs.getString("key")),
                        rs.getObject("id", UUID.class)))
                .list().forEach(e -> ids.put(e.getKey(), e.getValue()));
        List<Object[]> mentionRows = new ArrayList<>(model.mentions().size());
        for (Mention mention : model.mentions()) {
            ChunkRow chunk = byIndex.get(mention.chunkIndex());
            UUID entity = ids.get(mention.ref());
            if (chunk == null || entity == null) continue;
            mentionRows.add(new Object[]{entity, chunk.id(), documentId, chunk.sha256(), mention.defining()});
        }
        batch("INSERT INTO document.system_mention (entity_id, chunk_id, document_id, chunk_sha256, defining) "
                + "VALUES (?, ?, ?, ?, ?)", mentionRows);
        List<Object[]> relationRows = new ArrayList<>(model.relations().size());
        for (Relation relation : model.relations()) {
            ChunkRow chunk = byIndex.get(relation.chunkIndex());
            UUID source = ids.get(relation.source());
            UUID target = relation.target() == null ? null : ids.get(relation.target());
            if (chunk == null || source == null || (relation.target() != null && target == null)) continue;
            relationRows.add(new Object[]{documentId, relation.type().name(), source, target, relation.value(),
                    chunk.id(), chunk.sha256()});
        }
        batch("INSERT INTO document.system_relation (document_id, type, source_id, target_id, value, chunk_id, "
                + "chunk_sha256) VALUES (?, ?, ?, ?, ?, ?, ?)", relationRows);
        jdbc.sql("""
                        INSERT INTO document.system_model_state (document_id, extractor_version, chunks_sha256, entities,
                                                                 relations, truncated, extracted_at)
                        VALUES (:id, :version, :digest, :entities, :relations, :truncated, :now)""")
                .param("id", documentId).param("version", version).param("digest", digest)
                .param("entities", entityRows.size()).param("relations", relationRows.size())
                .param("truncated", model.truncated()).param("now", Timestamp.from(now))
                .update();
    }

    /** Removes the document's model (and its state, so the backfill would take it again). */
    public void delete(UUID documentId) {
        jdbc.sql("DELETE FROM document.system_entity WHERE document_id = :id").param("id", documentId).update();
        jdbc.sql("DELETE FROM document.system_model_state WHERE document_id = :id").param("id", documentId).update();
    }

    /**
     * Locks the next READY document without a model of this version, skipping rows another instance holds
     * (reference 11.1, multi-instance); the caller extracts it in the same transaction.
     */
    public Optional<UUID> lockNextPending(int version) {
        return jdbc.sql("""
                        SELECT d.id FROM document.document d
                        WHERE d.status = 'READY'
                          AND NOT EXISTS (SELECT 1 FROM document.system_model_state s
                                          WHERE s.document_id = d.id AND s.extractor_version = :version)
                        ORDER BY d.id
                        LIMIT 1
                        FOR NO KEY UPDATE OF d SKIP LOCKED""")
                .param("version", version).query(UUID.class).optional();
    }

    /**
     * Takes the document row lock (waiting for a holder), so ingestion-triggered and backfill extraction of the same
     * document serialise; the state read after it sees what the earlier holder committed (READ COMMITTED). False when
     * the document is gone: deleted between the ingestion's READY commit and this transaction (KVKK erasure, a user
     * delete), so the caller writes nothing instead of failing on the state row's foreign key.
     */
    public boolean lockDocument(UUID documentId) {
        return jdbc.sql("SELECT id FROM document.document WHERE id = :id FOR NO KEY UPDATE").param("id", documentId)
                .query(UUID.class).optional().isPresent();
    }

    /** READY documents waiting for a model of this version, over all accounts: a gauge, no id leaves. */
    public long pendingCount(int version) {
        return jdbc.sql("""
                        SELECT count(*) FROM document.document d WHERE d.status = 'READY' AND NOT EXISTS (
                            SELECT 1 FROM document.system_model_state s
                            WHERE s.document_id = d.id AND s.extractor_version = :version)""")
                .param("version", version).query(Long.class).single();
    }

    // --- reads (always with the reader's principals)

    /** Entities of the given types and keys across the readable READY documents, most documents first. */
    public List<SystemEntitySummary> find(String[] principals, List<SystemEntityType> types, List<String> keys,
                                          int limit) {
        if (types.isEmpty() || keys.isEmpty()) return List.of();
        return jdbc.sql("""
                        SELECT e.type, e.key, min(e.label) AS label, count(DISTINCT e.document_id) AS documents,
                               (SELECT count(*) FROM document.system_mention m JOIN document.system_entity x
                                    ON x.id = m.entity_id
                                WHERE x.type = e.type AND x.key = e.key
                                  AND EXISTS (SELECT 1 FROM document.document_principal p
                                              WHERE p.document_id = x.document_id AND p.principal = ANY(:principals))
                               ) AS mentions
                        FROM document.system_entity e JOIN document.document d ON d.id = e.document_id
                        WHERE e.type = ANY(CAST(:types AS text[])) AND e.key = ANY(CAST(:keys AS text[]))
                          AND d.status = 'READY' AND %s
                        GROUP BY e.type, e.key
                        ORDER BY count(DISTINCT e.document_id) DESC, e.type, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("types", names(types))
                .param("keys", keys.toArray(String[]::new)).param("limit", limit)
                .query(SystemModelRepository::summary).list();
    }

    /**
     * Browsing the system map: entities of one type (or all types when null) whose key starts with the folded
     * {@code prefix} (or every one when blank), most documents first.
     */
    public List<SystemEntitySummary> browse(String[] principals, SystemEntityType type, String prefix, int limit) {
        String like = prefix == null || prefix.isBlank() ? "%" : escapeLike(prefix) + "%";
        return jdbc.sql("""
                        SELECT e.type, e.key, min(e.label) AS label, count(DISTINCT e.document_id) AS documents,
                               count(m.chunk_id) AS mentions
                        FROM document.system_entity e
                        JOIN document.document d ON d.id = e.document_id
                        LEFT JOIN document.system_mention m ON m.entity_id = e.id
                        WHERE (CAST(:type AS text) IS NULL OR e.type = CAST(:type AS text))
                          AND e.key LIKE :like ESCAPE '\\' AND e.type <> 'STEP'
                          AND d.status = 'READY' AND %s
                        GROUP BY e.type, e.key
                        ORDER BY count(DISTINCT e.document_id) DESC, count(m.chunk_id) DESC, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("type", type == null ? null : type.name())
                .param("like", like).param("limit", limit)
                .query(SystemModelRepository::summary).list();
    }

    /** DEFINES values of the entity in readable READY documents, with their chunk; most recent documents first. */
    public List<SystemDefinition> definitions(String[] principals, SystemEntityType type, String key, int limit) {
        return jdbc.sql("""
                        SELECT r.value, %s
                        FROM document.system_entity e
                        JOIN document.system_relation r ON r.source_id = e.id AND r.type = 'DEFINES'
                        JOIN document.document_chunk c ON c.id = r.chunk_id
                        JOIN document.document d ON d.id = e.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE e.type = :type AND e.key = :key AND d.status = 'READY' AND %s
                        ORDER BY d.created_at DESC, r.id
                        LIMIT :limit""".formatted(SOURCE_COLUMNS, READABLE_ENTITY))
                .param("principals", principals).param("type", type.name()).param("key", key).param("limit", limit)
                .query((rs, n) -> new SystemDefinition(rs.getString("value"), source(rs, true))).list();
    }

    /** Relations to and from the entity in readable READY documents, each related entity once. */
    public List<SystemLink> links(String[] principals, SystemEntityType type, String key, int limit) {
        return jdbc.sql("""
                        SELECT DISTINCT ON (relation, outgoing, t_type, t_key) relation, outgoing, t_type, t_key,
                               t_label, t_ordinal
                        FROM (
                            SELECT r.type AS relation, true AS outgoing, t.type AS t_type, t.key AS t_key,
                                   t.label AS t_label, t.ordinal AS t_ordinal
                            FROM document.system_entity e
                            JOIN document.system_relation r ON r.source_id = e.id AND r.target_id IS NOT NULL
                            JOIN document.system_entity t ON t.id = r.target_id
                            JOIN document.document d ON d.id = e.document_id
                            WHERE e.type = :type AND e.key = :key AND d.status = 'READY' AND %1$s
                            UNION ALL
                            SELECT r.type, false, t.type, t.key, t.label, t.ordinal
                            FROM document.system_entity e
                            JOIN document.system_relation r ON r.target_id = e.id
                            JOIN document.system_entity t ON t.id = r.source_id
                            JOIN document.document d ON d.id = e.document_id
                            WHERE e.type = :type AND e.key = :key AND d.status = 'READY' AND %1$s
                        ) links
                        ORDER BY relation, outgoing, t_type, t_key, t_ordinal NULLS LAST
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("type", type.name()).param("key", key).param("limit", limit)
                .query((rs, n) -> new SystemLink(SystemRelationType.valueOf(rs.getString("relation")),
                        rs.getBoolean("outgoing"), SystemEntityType.valueOf(rs.getString("t_type")),
                        rs.getString("t_key"), rs.getString("t_label"), (Integer) rs.getObject("t_ordinal")))
                .list();
    }

    /** Where the entity is mentioned in readable READY documents: defining chunks first, then the newest documents. */
    public List<SystemSource> sources(String[] principals, SystemEntityType type, String key, int limit) {
        return jdbc.sql("""
                        SELECT m.defining, %s
                        FROM document.system_entity e
                        JOIN document.system_mention m ON m.entity_id = e.id
                        JOIN document.document_chunk c ON c.id = m.chunk_id
                        JOIN document.document d ON d.id = e.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE e.type = :type AND e.key = :key AND d.status = 'READY' AND %s
                        ORDER BY m.defining DESC, d.created_at DESC, c.chunk_index
                        LIMIT :limit""".formatted(SOURCE_COLUMNS, READABLE_ENTITY))
                .param("principals", principals).param("type", type.name()).param("key", key).param("limit", limit)
                .query((rs, n) -> source(rs, rs.getBoolean("defining"))).list();
    }

    /** The label one readable document gave the entity, or empty when no readable document has it. */
    public Optional<String> label(String[] principals, SystemEntityType type, String key) {
        return jdbc.sql("""
                        SELECT min(e.label) FROM document.system_entity e JOIN document.document d ON d.id = e.document_id
                        WHERE e.type = :type AND e.key = :key AND d.status = 'READY' AND %s
                        HAVING count(*) > 0""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("type", type.name()).param("key", key)
                .query(String.class).optional();
    }

    /** Entities of one readable READY document (empty when the reader may not read it). */
    public List<SystemEntitySummary> documentEntities(String[] principals, UUID documentId, int limit) {
        return jdbc.sql("""
                        SELECT e.type, e.key, e.label, 1 AS documents,
                               (SELECT count(*) FROM document.system_mention m WHERE m.entity_id = e.id) AS mentions
                        FROM document.system_entity e JOIN document.document d ON d.id = e.document_id
                        WHERE e.document_id = :id AND d.status = 'READY' AND %s
                        ORDER BY e.type, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("id", documentId).param("limit", limit)
                .query(SystemModelRepository::summary).list();
    }

    /** Chunks of one readable READY document with text and hash, in order. */
    public List<SourceChunk> documentChunks(String[] principals, UUID documentId, int limit) {
        return jdbc.sql("""
                        SELECT %s, c.content
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        LEFT JOIN document.collection col ON col.id = d.collection_id
                        WHERE c.document_id = :id AND d.status = 'READY' AND %s
                        ORDER BY c.chunk_index
                        LIMIT :limit""".formatted(SOURCE_COLUMNS, RetrievalRepository.READABLE_CHUNK))
                .param("principals", principals).param("id", documentId).param("limit", limit)
                .query((rs, n) -> new SourceChunk(rs.getObject("chunk_id", UUID.class),
                        rs.getObject("document_id", UUID.class), rs.getInt("page_number"),
                        DocumentFormat.valueOf(rs.getString("format")).unit(), rs.getString("hash"),
                        rs.getObject("collection_id", UUID.class), rs.getString("file_name"),
                        rs.getString("collection_name"), rs.getString("content")))
                .list();
    }

    /**
     * Retrieval enrichment (ADR-0054): chunks that define a CODE, TERM or SYSTEM named by the question, in readable
     * READY documents indexed with {@code model}, not already among {@code exclude}; at most {@code limit}.
     */
    public List<UUID> definingChunks(String[] principals, String model, List<String> keys, List<UUID> exclude,
                                     int limit) {
        if (keys.isEmpty() || limit <= 0) return List.of();
        return jdbc.sql("""
                        SELECT m.chunk_id
                        FROM document.system_entity e
                        JOIN document.system_mention m ON m.entity_id = e.id AND m.defining
                        JOIN document.document d ON d.id = e.document_id
                        WHERE e.type IN ('CODE', 'TERM', 'SYSTEM') AND e.key = ANY(CAST(:keys AS text[]))
                          AND d.status = 'READY' AND d.embedding_model = :model AND %s
                          AND NOT (m.chunk_id = ANY(CAST(:exclude AS uuid[])))
                        GROUP BY m.chunk_id
                        ORDER BY count(*) DESC, m.chunk_id
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("model", model).param("keys", keys.toArray(String[]::new))
                .param("exclude", exclude.stream().map(UUID::toString).toArray(String[]::new)).param("limit", limit)
                .query(UUID.class).list();
    }

    /** Distinct entities per type and documents with a model of this version, over readable READY documents. */
    public Map<SystemEntityType, Long> entityCounts(String[] principals) {
        Map<SystemEntityType, Long> counts = new EnumMap<>(SystemEntityType.class);
        for (SystemEntityType type : SystemEntityType.values()) counts.put(type, 0L);
        jdbc.sql("""
                        SELECT e.type, count(DISTINCT e.key) FROM document.system_entity e
                        JOIN document.document d ON d.id = e.document_id
                        WHERE d.status = 'READY' AND %s GROUP BY e.type""".formatted(READABLE_ENTITY))
                .param("principals", principals)
                .query((rs, n) -> Map.entry(SystemEntityType.valueOf(rs.getString(1)), rs.getLong(2))).list()
                .forEach(e -> counts.put(e.getKey(), e.getValue()));
        return counts;
    }

    /** Readable READY documents with a current model, and those still waiting for one. */
    public long[] documentCounts(String[] principals, int version) {
        return jdbc.sql("""
                        SELECT count(*) FILTER (WHERE s.extractor_version = :version),
                               count(*) FILTER (WHERE s.document_id IS NULL OR s.extractor_version <> :version)
                        FROM document.document d
                        LEFT JOIN document.system_model_state s ON s.document_id = d.id
                        WHERE d.status = 'READY' AND EXISTS (SELECT 1 FROM document.document_principal p
                                                             WHERE p.document_id = d.id AND p.principal = ANY(:principals))""")
                .param("principals", principals).param("version", version)
                .query((rs, n) -> new long[]{rs.getLong(1), rs.getLong(2)}).single();
    }

    // --- knowledge-health signals (shared documents the admin may read)

    /** Codes of readable shared documents that no readable shared document defines. */
    public List<SignalRow> undefinedCodes(String[] principals, int limit) {
        return jdbc.sql("""
                        SELECT e.key, min(e.label) AS label, array_agg(DISTINCT e.document_id) AS documents
                        FROM document.system_entity e JOIN document.document d ON d.id = e.document_id
                        WHERE e.type = 'CODE' AND d.status = 'READY' AND d.collection_id IS NOT NULL AND %s
                        GROUP BY e.key
                        HAVING NOT bool_or(EXISTS (SELECT 1 FROM document.system_relation r
                                                   WHERE r.source_id = e.id AND r.type = 'DEFINES'))
                        ORDER BY count(DISTINCT e.document_id) DESC, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("limit", limit)
                .query((rs, n) -> new SignalRow(SystemEntityType.CODE, rs.getString("key"), rs.getString("label"),
                        uuids(rs), List.of()))
                .list();
    }

    /** Abbreviations defined in two or more readable shared documents with more than one distinct text. */
    public List<SignalRow> termDefinitions(String[] principals, int limit) {
        return jdbc.sql("""
                        SELECT e.key, min(e.label) AS label, array_agg(DISTINCT e.document_id) AS documents,
                               array_agg(DISTINCT r.value) AS definitions
                        FROM document.system_entity e
                        JOIN document.system_relation r ON r.source_id = e.id AND r.type = 'DEFINES'
                        JOIN document.document d ON d.id = e.document_id
                        WHERE e.type = 'TERM' AND d.status = 'READY' AND d.collection_id IS NOT NULL AND %s
                        GROUP BY e.key
                        HAVING count(DISTINCT e.document_id) > 1 AND count(DISTINCT lower(r.value)) > 1
                        ORDER BY count(DISTINCT e.document_id) DESC, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("limit", limit)
                .query((rs, n) -> new SignalRow(SystemEntityType.TERM, rs.getString("key"), rs.getString("label"),
                        uuids(rs), Arrays.asList((String[]) rs.getArray("definitions").getArray())))
                .list();
    }

    /** Processes of readable shared documents to which no readable shared document gives an owner. */
    public List<SignalRow> processesWithoutOwner(String[] principals, int limit) {
        return jdbc.sql("""
                        SELECT e.key, min(e.label) AS label, array_agg(DISTINCT e.document_id) AS documents
                        FROM document.system_entity e JOIN document.document d ON d.id = e.document_id
                        WHERE e.type = 'PROCESS' AND d.status = 'READY' AND d.collection_id IS NOT NULL AND %s
                        GROUP BY e.key
                        HAVING NOT bool_or(EXISTS (
                            SELECT 1 FROM document.system_relation o
                            WHERE o.type = 'OWNED_BY' AND o.document_id = e.document_id
                              AND (o.source_id = e.id OR o.source_id IN (
                                  SELECT s.source_id FROM document.system_relation s
                                  WHERE s.target_id = e.id AND s.type = 'STEP_OF'))))
                        ORDER BY count(DISTINCT e.document_id) DESC, e.key
                        LIMIT :limit""".formatted(READABLE_ENTITY))
                .param("principals", principals).param("limit", limit)
                .query((rs, n) -> new SignalRow(SystemEntityType.PROCESS, rs.getString("key"), rs.getString("label"),
                        uuids(rs), List.of()))
                .list();
    }

    // --- helpers

    private void batch(String sql, List<Object[]> rows) {
        for (int from = 0; from < rows.size(); from += INSERT_BATCH) {
            template.batchUpdate(sql, rows.subList(from, Math.min(rows.size(), from + INSERT_BATCH)));
        }
    }

    private static SystemEntitySummary summary(ResultSet rs, int n) throws SQLException {
        return new SystemEntitySummary(SystemEntityType.valueOf(rs.getString("type")), rs.getString("key"),
                rs.getString("label"), rs.getLong("documents"), rs.getLong("mentions"));
    }

    private static SystemSource source(ResultSet rs, boolean defining) throws SQLException {
        return new SystemSource(rs.getObject("document_id", UUID.class), rs.getString("file_name"),
                rs.getInt("page_number"), DocumentFormat.valueOf(rs.getString("format")).unit(),
                rs.getObject("chunk_id", UUID.class), rs.getString("hash"), rs.getObject("collection_id", UUID.class),
                rs.getString("collection_name"), defining);
    }

    private static List<UUID> uuids(ResultSet rs) throws SQLException {
        Object[] values = (Object[]) rs.getArray("documents").getArray();
        List<UUID> ids = new ArrayList<>(values.length);
        for (Object value : values) ids.add((UUID) value);
        return ids.stream().sorted().limit(20).toList();
    }

    private static String[] names(List<SystemEntityType> types) {
        return types.stream().map(Enum::name).toArray(String[]::new);
    }

    /** The prefix as a LIKE pattern literal: %, _ and the escape itself match only themselves. */
    static String escapeLike(String prefix) {
        return prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
