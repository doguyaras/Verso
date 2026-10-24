package com.verso.document.repository;

import com.verso.document.api.enums.DocumentFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The stored text of one page (ADR-0035). The reader filter is the one every listing and retrieval uses
 * ({@link DocumentRepository#READABLE}, ADR-0028), inside the statement: a document the reader cannot read is not
 * found, exactly like a missing one, and its text never leaves the database.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class DocumentPageRepository {

    private final JdbcClient jdbc;

    public DocumentPageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The readable document with page {@code number}; {@code text} is null when the document has no such page. At most
     * {@code maxChars + 1} characters are read, so a caller can tell a cut page without loading a longer one.
     */
    public Optional<PageRow> find(String[] principals, UUID documentId, int number, int maxChars) {
        return jdbc.sql("SELECT d.format, d.page_count, left(p.content, :chars) AS text FROM document.document d "
                        + "LEFT JOIN document.document_page p ON p.document_id = d.id AND p.page_number = :number "
                        + "WHERE d.id = :id AND " + DocumentRepository.READABLE)
                .param("chars", maxChars + 1).param("number", number).param("id", documentId)
                .param("principals", principals)
                .query((rs, rowNum) -> new PageRow(DocumentFormat.valueOf(rs.getString("format")),
                        (Integer) rs.getObject("page_count"), rs.getString("text")))
                .optional();
    }

    /** A readable document and, when it has the asked page, the page's text (bounded). */
    public record PageRow(DocumentFormat format, Integer pageCount, String text) {
    }
}
