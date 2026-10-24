package com.verso.qa.repository;

import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The qa module's own database access (ADR-0023): a JdbcClient and transactions on the svc_qa pool. Deliberately not
 * exposed as DataSource, JdbcClient or transaction manager beans: any of those would compete with (or switch off) the
 * application's auto-configured ones, and the document module must never be handed this connection by type.
 *
 * <p>Transactions are short and hold no remote call (llm-rules 5.1): the turn is stored after the model answered.
 */
public final class QaDatabase implements AutoCloseable {

    private final DataSource pool;
    private final LazyConnectionDataSourceProxy lazy;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public QaDatabase(DataSource pool) {
        this.pool = pool;
        // A connection is taken at the first statement, not when a transaction opens (as Boot 4.1 does for the
        // application DataSource, reference 10.5).
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(pool);
        this.lazy = lazy;
        // Known defaults, so that opening a transaction never fetches a connection just to read them: an unreachable
        // database then fails at the first statement as a DataAccessException (503 SERVICE_UNAVAILABLE), not as a
        // CannotCreateTransactionException (500) after a doubled connection timeout. These are PostgreSQL's and
        // Hikari's defaults; the pool hands out connections in exactly this state.
        lazy.setDefaultAutoCommit(true);
        lazy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        this.jdbc = JdbcClient.create(lazy);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(lazy));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** True while {@link #inTransaction} runs on this thread (the outbox writer is MANDATORY, ADR-0039). */
    public boolean inTransactionNow() {
        return TransactionSynchronizationManager.hasResource(lazy);
    }

    /** Runs {@code work} in one transaction of the qa schema. */
    public <T> T inTransaction(Function<TransactionStatus, T> work) {
        return transaction.execute(work::apply);
    }

    /** The health check of the qa pool (readiness): one trivial statement as svc_qa. */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    /** Closes the pool at shutdown (the context calls it). */
    @Override
    public void close() throws Exception {
        if (pool instanceof AutoCloseable closeable) closeable.close();
    }
}
