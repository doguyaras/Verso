package com.verso.connector.repository;

import com.verso.platform.core.outbox.OutboxStore;
import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The connector module's own database access (ADR-0038): a JdbcClient and transactions on the svc_connector pool. Deliberately
 * not exposed as DataSource, JdbcClient or transaction manager beans (same reason as QaDatabase, ADR-0023): any of
 * those would compete with the application's auto-configured ones, and no other module may be handed this connection
 * by type.
 */
public final class ConnectorDatabase implements AutoCloseable {

    /** A hung statement must not hold a request or the worker longer than this (the role's statement_timeout is 10 s). */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource pool;
    private final LazyConnectionDataSourceProxy lazy;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public ConnectorDatabase(DataSource pool) {
        this.pool = pool;
        // A connection is taken at the first statement, not when a transaction opens (reference 10.5), with the
        // defaults the pool hands out, so an unreachable database fails at the first statement as a DataAccessException.
        this.lazy = new LazyConnectionDataSourceProxy(pool);
        lazy.setDefaultAutoCommit(true);
        lazy.setDefaultTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        JdbcTemplate template = new JdbcTemplate(lazy);
        template.setQueryTimeout(QUERY_TIMEOUT_SECONDS);
        this.jdbc = JdbcClient.create(template);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(lazy));
    }

    public JdbcClient jdbc() {
        return jdbc;
    }

    /** Runs {@code work} in one transaction of the connector schema. */
    public <T> T inTransaction(Function<TransactionStatus, T> work) {
        return transaction.execute(work::apply);
    }

    /** {@link #inTransaction} for callers that need no transaction status (workers stay free of transaction types). */
    public <T> T transactionally(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    /** True while {@link #inTransaction} runs on this thread (the outbox writer is MANDATORY, ADR-0039). */
    public boolean inTransactionNow() {
        return TransactionSynchronizationManager.hasResource(lazy);
    }

    /** The module's outbox table on this pool; rows are written only inside {@link #inTransaction}. */
    public OutboxStore outboxStore() {
        return new OutboxStore("connector.outbox_event", jdbc, this::inTransactionNow);
    }

    /** One trivial statement as svc_connector (tests, diagnostics). */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    /** Closes the pool at shutdown (the context calls it). */
    @Override
    public void close() throws Exception {
        if (pool instanceof AutoCloseable closeable) closeable.close();
    }
}
