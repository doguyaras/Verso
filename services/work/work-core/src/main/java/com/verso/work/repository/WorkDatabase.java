package com.verso.work.repository;

import com.verso.platform.core.outbox.OutboxStore;
import java.sql.Connection;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The work module's own database access (ADR-0057): a JdbcClient and transactions on the svc_work pool. Deliberately
 * not exposed as DataSource, JdbcClient or transaction manager beans (same reason as QaDatabase, ADR-0023).
 */
public final class WorkDatabase implements AutoCloseable {

    /** A hung statement must not hold a request or a run longer than this (the role's statement_timeout is 10 s). */
    private static final int QUERY_TIMEOUT_SECONDS = 5;

    private final DataSource pool;
    private final LazyConnectionDataSourceProxy lazy;
    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public WorkDatabase(DataSource pool) {
        this.pool = pool;
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

    /** Runs {@code work} in one transaction of the work schema; services stay free of transaction types. */
    public <T> T transactionally(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    /** True while {@link #transactionally} runs on this thread (the outbox writer is MANDATORY, ADR-0039). */
    public boolean inTransactionNow() {
        return TransactionSynchronizationManager.hasResource(lazy);
    }

    /** The health check of the work pool (readiness): one trivial statement as svc_work. */
    public void ping() {
        jdbc.sql("SELECT 1").query(Integer.class).single();
    }

    /** The module's outbox table on this pool; rows are written only inside {@link #transactionally}. */
    public OutboxStore outboxStore() {
        return new OutboxStore("work.outbox_event", jdbc, this::inTransactionNow);
    }

    @Override
    public void close() throws Exception {
        if (pool instanceof AutoCloseable closeable) closeable.close();
    }
}
