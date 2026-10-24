package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.audit.worker.AuditRetentionJob;
import com.verso.audit.worker.AuditRetentionJob.Result;
import com.verso.support.AuditTables;
import com.verso.support.VersoTestEnvironment;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Audit retention (ADR-0026, KVKK storage limitation) on the real database with the production default of 365 days:
 * rows older than that go through audit.purge_expired, nothing younger is touched, batches of 2 make the loop run, and
 * with several instances only the lock holder deletes.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.audit.purge-batch-size=2"})
@VersoTestEnvironment
class AuditRetentionTest {

    @Autowired
    AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        AuditTables.reset();
    }

    @Test
    void runOnce_whenRowsAreOlderThanTheRetention_deletesOnlyThemInBatches() {
        Instant now = Instant.now();
        for (int i = 0; i < 5; i++) AuditTables.insertAt(now.minus(Duration.ofDays(366 + i)).toString(), "expired-" + i, "QUESTION_ASKED");
        AuditTables.insertAt(now.minus(Duration.ofDays(364)).toString(), "kept-364", "QUESTION_ASKED");
        AuditTables.insertAt(now.minus(Duration.ofDays(31)).toString(), "kept-31", "QUESTION_ASKED");
        AuditTables.insertAt(now.toString(), "kept-now", "QUESTION_ASKED");

        Result result = job.runOnce();

        assertThat(result.deleted()).isEqualTo(5);
        assertThat(result.skipped()).isFalse();
        assertThat(AuditTables.strings("select actor_sub from audit.event order by actor_sub"))
                .containsExactly("kept-31", "kept-364", "kept-now");
        assertThat(job.runOnce().deleted()).as("idempotent").isZero();
    }

    @Test
    void runOnce_whenAnotherInstanceHoldsTheLock_skipsAndDeletesNothing() throws Exception {
        AuditTables.insertAt(Instant.now().minus(Duration.ofDays(400)).toString(), "expired", "QUESTION_ASKED");
        try (Connection other = AuditTables.admin(); Statement st = other.createStatement()) {
            // Another instance mid-run: the same advisory key, held by a different session (reference 10.4).
            st.execute("select pg_advisory_lock(hashtextextended('audit.retention', 0))");

            Result result = job.runOnce();

            assertThat(result.skipped()).isTrue();
            assertThat(result.deleted()).isZero();
            assertThat(AuditTables.count("select count(*) from audit.event")).isOne();
            st.execute("select pg_advisory_unlock(hashtextextended('audit.retention', 0))");
        }
        assertThat(job.runOnce().deleted()).as("the next round deletes").isOne();
    }
}
