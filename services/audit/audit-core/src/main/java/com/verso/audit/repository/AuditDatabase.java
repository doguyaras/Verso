package com.verso.audit.repository;

import java.sql.Connection;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The audit module's own database access (ADR-0026): a JdbcClient and transactions on the svc_audit pool. Deliberately
 * not exposed as DataSource, JdbcClient or transaction manager beans (same reason as QaDatabase, ADR-0023): any of
 * those would compete with the application's auto-configured ones, and no other module may be handed this connection
 * by type.
 */
public final class AuditDatabase implements AutoCloseable {

    /** A hung statement must not hold a request longer than this (the role's statement_timeout is 10 s). */
    private static final int QUERY_TIMEOUT_SECONDS = 3;

    private final DataSource pool;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public AuditDatabase(DataSource pool) {
        this.pool = pool;
        // A connection is taken at the first statement, not when a transaction opens (reference 10.5), with the
        // defaults the pool hands out, so an unreachable database fails at the first statement as a DataAccessException.
        LazyConnectionDataSourceProxy lazy = new LazyConnectionDataSourceProxy(pool);
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

    /** Runs {@code work} in one transaction of the audit schema. */
    public <T> T inTransaction(Function<TransactionStatus, T> work) {
        return transaction.execute(work::apply);
    }

    /** One trivial statement as svc_audit (tests, diagnostics). */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    /** Closes the pool at shutdown (the context calls it). */
    @Override
    public void close() throws Exception {
        if (pool instanceof AutoCloseable closeable) closeable.close();
    }
}
