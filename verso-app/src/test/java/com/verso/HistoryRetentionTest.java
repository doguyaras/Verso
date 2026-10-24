package com.verso;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.worker.HistoryRetentionJob;
import com.verso.qa.worker.HistoryRetentionJob.Result;
import com.verso.support.QaTables;
import com.verso.support.VersoTestEnvironment;
import java.sql.Connection;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * History retention (ADR-0023, KVKK storage limitation) on the real database with the production default of 90 days:
 * a conversation lives 90 days from its start, with all its turns, citations, ratings and its title (the first
 * question), even when it was continued yesterday; the activity rows of expired days go; the anonymous rollups stay.
 * With several instances only the lock holder deletes. Batches of 2 make every step loop.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.qa.history.purge-batch-size=2"})
@VersoTestEnvironment
class HistoryRetentionTest {

    private static final ZoneId ZONE = ZoneId.of("Europe/Istanbul");

    @Autowired
    HistoryRetentionJob job;

    private final String account = "acct-retention-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        QaTables.reset();
    }

    @Test
    void runOnce_whenConversationsAreOlderThanTheRetention_deletesThemWithEveryTurnAndKeepsTheRest() {
        Instant now = Instant.now();
        Instant expired = now.minus(Duration.ofDays(91));
        Instant kept = now.minus(Duration.ofDays(89));
        // Five expired conversations (more than one batch of 2), each with a citation and a rating.
        for (int i = 0; i < 5; i++) {
            UUID old = conversation(expired, "MarkerOldTitle");
            UUID turn = turn(old, expired);
            QaTables.update("insert into qa.turn_citation (turn_id, citation_number, document_id, page, unit) "
                    + "values (?, 1, ?, 1, 'PAGE')", turn, UUID.randomUUID());
            QaTables.update("insert into qa.turn_feedback (turn_id, rating) values (?, 'UP')", turn);
        }
        // Started 91 days ago and continued yesterday: its title is the first question, so it goes as a whole.
        UUID continued = conversation(expired, "MarkerContinuedTitle");
        turn(continued, expired);
        turn(continued, now.minus(Duration.ofDays(1)));
        UUID active = conversation(kept, "kept");
        UUID recent = turn(active, kept);
        LocalDate expiredDay = LocalDate.ofInstant(expired, ZONE);
        QaTables.update("insert into qa.active_account_day (day, account_id) values (?, ?), (?, ?)",
                expiredDay, account, LocalDate.ofInstant(kept, ZONE), account);
        QaTables.update("insert into qa.usage_daily (day, questions) values (?, 7)", expiredDay);

        Result result = job.runOnce();

        assertThat(result.skipped()).isFalse();
        assertThat(result.turns()).isEqualTo(7);
        assertThat(result.conversations()).isEqualTo(6);
        assertThat(result.activityRows()).isOne();
        assertThat(QaTables.count("select count(*) from qa.conversation where title like 'Marker%'"))
                .as("no first question survives in a title").isZero();
        assertThat(QaTables.count("select count(*) from qa.conversation")).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn")).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn where id = ?", recent)).isOne();
        assertThat(QaTables.count("select count(*) from qa.turn_citation")).as("citations go with their turns").isZero();
        assertThat(QaTables.count("select count(*) from qa.turn_feedback")).as("ratings go with their turns").isZero();
        assertThat(QaTables.count("select count(*) from qa.active_account_day")).isOne();
        assertThat(QaTables.count("select questions from qa.usage_daily where day = ?", expiredDay))
                .as("anonymous counts outlive the history").isEqualTo(7);

        assertThat(job.runOnce()).as("idempotent").isEqualTo(new Result(0, 0, 0, false));
    }

    /** Multi-instance (reference 11.1): while another instance holds the lock, this one deletes nothing. */
    @Test
    void runOnce_whenAnotherInstanceHoldsTheLock_skipsAndLeavesTheRows() throws Exception {
        Instant old = Instant.now().minus(Duration.ofDays(120));
        UUID conversation = conversation(old, "t");
        turn(conversation, old);
        try (Connection other = QaTables.admin(); Statement st = other.createStatement()) {
            other.setAutoCommit(false);
            st.execute("select pg_advisory_xact_lock(hashtextextended('qa.history-retention', 0))");

            Result result = job.runOnce();

            assertThat(result.skipped()).isTrue();
            assertThat(QaTables.count("select count(*) from qa.turn where conversation_id = ?", conversation)).isOne();
            other.rollback();
        }
        assertThat(job.runOnce().conversations()).isOne();
        assertThat(QaTables.count("select count(*) from qa.conversation where id = ?", conversation)).isZero();
    }

    private UUID conversation(Instant at, String title) {
        UUID id = UUID.randomUUID();
        QaTables.update("insert into qa.conversation (id, account_id, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                id, account, title, Timestamp.from(at), Timestamp.from(at));
        return id;
    }

    private UUID turn(UUID conversation, Instant at) {
        UUID id = UUID.randomUUID();
        QaTables.update("insert into qa.turn (id, conversation_id, account_id, question, question_hash, answer, outcome, "
                        + "mode, chat_model, retrieval_ms, chat_ms, total_ms, day, created_at) values (?, ?, ?, 'q', "
                        + "sha256('q'::bytea), 'a', 'ANSWERED', 'local', 'm', 1, 1, 2, ?, ?)",
                id, conversation, account, LocalDate.ofInstant(at, ZONE), Timestamp.from(at));
        QaTables.update("update qa.conversation set updated_at = greatest(updated_at, ?) where id = ?",
                Timestamp.from(at), conversation);
        return id;
    }
}
