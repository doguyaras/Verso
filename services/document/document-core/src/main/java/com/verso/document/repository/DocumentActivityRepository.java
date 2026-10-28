package com.verso.document.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The document module's reads for the activity centre (ADR-0058): shared-collection documents added or changed in a
 * window, per UTC day and collection, and the system-model extractions per day. Every statement carries the viewer's
 * principals (ADR-0028, the same predicate as the document list): a collection the viewer cannot read never shows,
 * and personal documents never count (collection_id IS NOT NULL). Counts, the collection's name and a few readable file
 * names only; never the uploader.
 */
@Repository
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentActivityRepository {

    /** Documents of one collection on one day: added (created that day) and changed (an older document touched). */
    public record DayChange(Instant day, UUID collectionId, String collectionName, int added, int changed, int failed,
                            Instant latest, List<String> sampleNames) {
    }

    /** System-model extractions of one day over readable shared documents. */
    public record ExtractionDay(Instant day, int documents, long entities, long relations, Instant latest) {
    }

    private static final int SAMPLE = 3;

    private final JdbcClient jdbc;

    public DocumentActivityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * No array slice ("[1:3]") and no "::" cast here: JdbcClient's named-parameter parser reads ":3" as a parameter
     * named "3" and the statement fails (the activity test's "partial" in CI). The sample is taken with a window rank.
     */
    static final String CHANGES_SQL = """
            SELECT x.day, x.collection_id, x.name,
                   count(*) FILTER (WHERE x.created_at >= x.day) AS added,
                   count(*) FILTER (WHERE x.created_at < x.day) AS changed,
                   count(*) FILTER (WHERE x.status = 'FAILED') AS failed,
                   max(x.updated_at) AS latest,
                   array_agg(x.file_name ORDER BY x.updated_at DESC) FILTER (WHERE x.rank <= %d) AS names
            FROM (SELECT date_trunc('day', d.updated_at, 'UTC') AS day, d.collection_id, col.name, d.created_at,
                         d.updated_at, d.status, d.file_name,
                         row_number() OVER (PARTITION BY date_trunc('day', d.updated_at, 'UTC'), d.collection_id
                                            ORDER BY d.updated_at DESC, d.id DESC) AS rank
                  FROM document.document d JOIN document.collection col ON col.id = d.collection_id
                  WHERE d.collection_id IS NOT NULL AND d.updated_at >= :from AND d.updated_at < :to
                    AND (CAST(:collection AS uuid) IS NULL OR d.collection_id = CAST(:collection AS uuid))
                    AND %s) x
            GROUP BY x.day, x.collection_id, x.name ORDER BY latest DESC LIMIT :limit
            """.formatted(SAMPLE, DocumentRepository.READABLE);

    static final String EXTRACTIONS_SQL = """
            SELECT date_trunc('day', s.extracted_at, 'UTC') AS day, count(*) AS documents,
                   sum(s.entities) AS entities, sum(s.relations) AS relations, max(s.extracted_at) AS latest
            FROM document.system_model_state s JOIN document.document d ON d.id = s.document_id
            WHERE d.collection_id IS NOT NULL AND s.extracted_at >= :from AND s.extracted_at < :to AND %s
            GROUP BY 1 ORDER BY latest DESC LIMIT :limit
            """.formatted(DocumentRepository.READABLE);

    public List<DayChange> changes(String[] principals, Instant from, Instant to, UUID collection, int limit) {
        return jdbc.sql(CHANGES_SQL)
                .param("principals", principals).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("collection", collection).param("limit", limit)
                .query((rs, n) -> new DayChange(rs.getTimestamp("day").toInstant(),
                        rs.getObject("collection_id", UUID.class), rs.getString("name"), rs.getInt("added"),
                        rs.getInt("changed"), rs.getInt("failed"), rs.getTimestamp("latest").toInstant(),
                        names(rs.getArray("names")))).list();
    }

    public List<ExtractionDay> extractions(String[] principals, Instant from, Instant to, int limit) {
        return jdbc.sql(EXTRACTIONS_SQL)
                .param("principals", principals).param("from", Timestamp.from(from)).param("to", Timestamp.from(to))
                .param("limit", limit)
                .query((rs, n) -> new ExtractionDay(rs.getTimestamp("day").toInstant(), rs.getInt("documents"),
                        rs.getLong("entities"), rs.getLong("relations"), rs.getTimestamp("latest").toInstant())).list();
    }

    private static List<String> names(java.sql.Array array) throws java.sql.SQLException {
        if (array == null) return List.of();
        Object[] values = (Object[]) array.getArray();
        return java.util.Arrays.stream(values).map(String::valueOf).toList();
    }
}
