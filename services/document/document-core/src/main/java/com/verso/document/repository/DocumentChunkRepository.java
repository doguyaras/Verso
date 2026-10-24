package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The chunks of one readable document, a page at a time (ADR-0036). The reader filter is the one every listing,
 * retrieval and the page viewer use ({@link DocumentRepository#READABLE}, ADR-0028), inside the same statement as the
 * chunk read: a document the reader cannot read returns no row, exactly like a missing one, and none of its text
 * leaves the database. The ordered read and the count use the (document_id, chunk_index) unique index.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentChunkRepository {

    private final JdbcClient jdbc;

    public DocumentChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Empty when the document is missing or unreadable; otherwise its format, chunk total and the asked page of chunks
     * (possibly none: past the end, or while the document is still processed). At most {@code maxChars} code points of
     * each chunk are read; {@code chars} is the whole length.
     */
    public Optional<ChunkPage> find(String[] principals, UUID documentId, int page, int size, int maxChars) {
        List<Row> rows = jdbc.sql("WITH doc AS (SELECT d.id, d.format FROM document.document d "
                        + "WHERE d.id = :id AND " + DocumentRepository.READABLE + ") "
                        + "SELECT doc.format, "
                        + "(SELECT count(*) FROM document.document_chunk k WHERE k.document_id = doc.id) AS total, "
                        + "c.chunk_index, c.page_number, c.chars, c.text FROM doc LEFT JOIN LATERAL ("
                        + "SELECT k.chunk_index, k.page_number, char_length(k.content) AS chars, "
                        + "left(k.content, :chars) AS text FROM document.document_chunk k WHERE k.document_id = doc.id "
                        + "ORDER BY k.chunk_index LIMIT :size OFFSET :offset) c ON true ORDER BY c.chunk_index")
                .param("id", documentId).param("principals", principals).param("chars", maxChars)
                .param("size", size).param("offset", (long) page * size)
                .query((rs, rowNum) -> new Row(DocumentFormat.valueOf(rs.getString("format")), rs.getLong("total"),
                        (Integer) rs.getObject("chunk_index"), (Integer) rs.getObject("page_number"),
                        (Integer) rs.getObject("chars"), rs.getString("text")))
                .list();
        if (rows.isEmpty()) return Optional.empty();
        List<Chunk> chunks = rows.stream().filter(r -> r.index() != null)
                .map(r -> new Chunk(r.index(), r.number(), r.chars(), r.text())).toList();
        return Optional.of(new ChunkPage(rows.getFirst().format(), rows.getFirst().total(), chunks));
    }

    private record Row(DocumentFormat format, long total, Integer index, Integer number, Integer chars, String text) {
    }

    /** One chunk: its position, page or section, whole length and its leading text. */
    public record Chunk(int index, int number, int chars, String text) {
    }

    /** A readable document's format, its chunk total and one page of its chunks. */
    public record ChunkPage(DocumentFormat format, long total, List<Chunk> chunks) {
    }
}
