package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.audit.worker.AuditRetentionJob;
import com.verso.audit.worker.AuditRetentionJob.Result;
import com.verso.support.AuditTables;
import com.verso.support.VersoTestEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Audit retention at the documented floor, 30 days (ADR-0026; V4). The cutoff is computed by the database from its
 * own clock, so the application clock cannot push it under the function's floor: before V4, a JVM clock a few
 * milliseconds ahead of the database's made every batch fail at this value and nothing was ever deleted. The rows are
 * written relative to the database's now() for the same reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.audit.retention-days=30"})
@VersoTestEnvironment
class AuditRetentionFloorTest {

    @Autowired
    AuditRetentionJob job;

    @BeforeEach
    void setUp() {
        AuditTables.reset();
    }

    @Test
    void runOnce_atTheThirtyDayFloor_deletesRowsOlderThanThirtyDaysByTheDatabaseClock() {
        insertRelative("-30 days -1 second", "expired-by-a-second");
        insertRelative("-45 days", "expired-45");
        insertRelative("-30 days +1 minute", "kept-29-days");
        insertRelative("0 seconds", "kept-now");

        Result result = job.runOnce();

        assertThat(result.deleted()).isEqualTo(2);
        assertThat(result.skipped()).isFalse();
        assertThat(AuditTables.strings("select actor_sub from audit.event order by actor_sub"))
                .containsExactly("kept-29-days", "kept-now");
        assertThat(job.runOnce().deleted()).as("idempotent").isZero();
    }

    /** A row at now() + offset by the database clock, as the superuser (the application cannot choose the time). */
    private static void insertRelative(String offset, String actor) {
        AuditTables.update("insert into audit.event (occurred_at, actor_sub, action, outcome, target_type, trace_id, mode) "
                + "values (now() + cast(? as interval), ?, 'QUESTION_ASKED', 'SUCCESS', 'DOCUMENT', repeat('a', 32), 'local')",
                offset, actor);
    }
}
