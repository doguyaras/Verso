package com.verso.document.repository;

import com.verso.document.api.dto.RetrievedPassage;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Vector search over document_chunk (ADR-0011, llm-rules 4.1/6.1). The account and the embedding model are conditions
 * of the vector query itself: another account's chunk, or a chunk of another model, is never a candidate. Call inside
 * a transaction: the HNSW scan settings are SET LOCAL.
 */
@Repository
// Web application only: the one-shot migrate run (no web server, no application DataSource) needs none of it.
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class RetrievalRepository {

    private final JdbcClient jdbc;

    public RetrievalRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * With a filter, a plain HNSW scan stops after ef_search candidates and can return fewer rows than asked for
     * (phase 4 db review D4: 2 of 5 for a small account). The iterative scan keeps going until enough rows pass the
     * filter; relaxed order is re-sorted by the caller.
     */
    public void prepareFilteredScan(int topK) {
        jdbc.sql("SELECT set_config('hnsw.iterative_scan', 'relaxed_order', true), "
                        + "set_config('hnsw.ef_search', :ef, true)")
                .param("ef", String.valueOf(Math.max(40, topK * 4))).query((rs, n) -> 1).single();
    }

    public List<RetrievedPassage> nearest(String accountId, String model, float[] question, int topK) {
        String vector = IngestionRepository.vectorLiteral(question);
        return jdbc.sql("""
                        SELECT c.document_id, d.file_name, c.page_number, c.content,
                               1 - (c.embedding <=> CAST(:q AS extensions.vector)) AS similarity
                        FROM document.document_chunk c
                        JOIN document.document d ON d.id = c.document_id
                        WHERE c.account_id = :account AND c.embedding_model = :model AND d.status = 'READY'
                        ORDER BY c.embedding <=> CAST(:q AS extensions.vector)
                        LIMIT :k
                        """)
                .param("q", vector).param("account", accountId).param("model", model).param("k", topK)
                .query((rs, n) -> new RetrievedPassage(rs.getObject("document_id", UUID.class), rs.getString("file_name"),
                        rs.getInt("page_number"), rs.getString("content"), rs.getDouble("similarity")))
                .list();
    }

    /** True when the account has chunks embedded by another model: comparing them would be meaningless (6.1). */
    public boolean hasChunksOfAnotherModel(String accountId, String model) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM document.document_chunk WHERE account_id = :account "
                        + "AND embedding_model <> :model)")
                .param("account", accountId).param("model", model).query(Boolean.class).single();
    }
}
