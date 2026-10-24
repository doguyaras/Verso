package com.verso;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.outbox.DocumentAuditOutbox;
import com.verso.document.outbox.DocumentAuditOutboxHandler;
import com.verso.document.outbox.DocumentOutbox;
import com.verso.platform.core.outbox.OutboxProperties;
import com.verso.platform.core.outbox.OutboxRelay;
import com.verso.platform.core.outbox.OutboxStore.OutboxRow;
import com.verso.qa.outbox.QaOutbox;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.service.QaAudit;
import com.verso.support.AuditTables;
import com.verso.support.VersoTestEnvironment;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The durable audit path against the real database (ADR-0039, reference 11.2/11.3): the outbox row commits and rolls
 * back with the business transaction; the relay is idempotent by the event id (a duplicate delivery leaves one audit
 * row); a process that dies between the commit and the relay, or between the audit insert and the outbox delete, still
 * ends with exactly one audit row once a relay runs again; and the outbox age gauge reports a waiting row.
 *
 * <p>Eager delivery is off here so the test decides when the relay runs (the poll is off in every test context).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"management.server.port=0", "verso.outbox.eager-delivery=false"})
@VersoTestEnvironment
class AuditOutboxTest {

    @Autowired
    DocumentAuditOutbox documentAudit;

    @Autowired
    DocumentOutbox documentOutbox;

    @Autowired
    DocumentAuditOutboxHandler documentHandler;

    @Autowired
    TransactionTemplate documentTransaction;

    @Autowired
    JdbcClient documentJdbc;

    @Autowired
    QaAudit qaAudit;

    @Autowired
    QaOutbox qaOutbox;

    @Autowired
    QaDatabase qaDb;

    @Autowired
    OutboxProperties properties;

    @Autowired
    MeterRegistry meters;

    private final String sub = "acct-outbox-" + UUID.randomUUID();

    @BeforeEach
    void setUp() {
        AuditTables.reset();
        AuditTables.update("DELETE FROM document.outbox_event");
        AuditTables.update("DELETE FROM qa.outbox_event");
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject(sub).issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60)).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.NO_AUTHORITIES));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void outboxRow_whenTheBusinessTransactionRollsBack_isNotWritten() {
        UUID documentId = UUID.randomUUID();
        documentTransaction.executeWithoutResult(status -> {
            documentAudit.append(uploaded(documentId));
            assertThat(documentJdbc.sql("SELECT count(*) FROM document.outbox_event").query(Long.class).single())
                    .as("inside the transaction").isOne();
            assertThat(pending("document")).as("not visible before the commit").isZero();
            status.setRollbackOnly();
        });
        assertThat(pending("document")).isZero();

        qaDb.inTransaction(status -> {
            qaAudit.conversationDeletedInTransaction(UUID.randomUUID());
            status.setRollbackOnly();
            return null;
        });
        assertThat(pending("qa")).isZero();

        documentTransaction.executeWithoutResult(status -> documentAudit.append(uploaded(documentId)));
        assertThat(pending("document")).as("committed with the transaction").isOne();
        assertThat(audited()).as("not delivered yet").isZero();
    }

    @Test
    void outboxWriter_outsideTheModuleTransaction_isRefused() {
        assertThatThrownBy(() -> documentAudit.append(uploaded(UUID.randomUUID())))
                .isInstanceOf(IllegalStateException.class);
        // A document transaction is not a qa transaction: the qa writer needs its own.
        documentTransaction.executeWithoutResult(status -> assertThatThrownBy(
                () -> qaAudit.conversationDeletedInTransaction(UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class));
        assertThat(pending("qa")).isZero();
    }

    @Test
    void relay_whenTheSameRowIsDeliveredTwice_writesOneAuditRow() {
        documentTransaction.executeWithoutResult(status -> documentAudit.append(uploaded(UUID.randomUUID())));
        List<Map<String, Object>> before = AuditTables.rowsOf(
                "SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload FROM document.outbox_event");

        assertThat(documentOutbox.relay().runOnce().delivered()).isOne();
        assertThat(pending("document")).as("deleted after delivery").isZero();
        assertThat(audited()).isOne();

        // The same event arrives again (a lost delete, a second relay): the unique event id makes it a no-op.
        Map<String, Object> row = before.getFirst();
        AuditTables.update("INSERT INTO document.outbox_event (id, aggregate_type, aggregate_id, event_type, payload) "
                + "VALUES (?, ?, ?, ?, CAST(? AS jsonb))", row.get("id"), row.get("aggregate_type"),
                row.get("aggregate_id"), row.get("event_type"), row.get("payload"));
        assertThat(documentOutbox.relay().runOnce().delivered()).isOne();
        assertThat(pending("document")).isZero();
        assertThat(audited()).as("still one row").isOne();
        assertThat(AuditTables.count("SELECT count(*) FROM audit.event WHERE event_id = ?", row.get("id"))).isOne();
    }

    @Test
    void crash_betweenAuditInsertAndOutboxDelete_endsWithExactlyOneAuditRowAfterRestart() throws Exception {
        documentTransaction.executeWithoutResult(status -> documentAudit.append(uploaded(UUID.randomUUID())));
        // A relay claims the row and stores it, then the process dies before the delete.
        List<OutboxRow> claimed = documentOutbox.store().claimPending(10, Duration.ofSeconds(120), UUID.randomUUID());
        assertThat(claimed).hasSize(1);
        documentHandler.handle(claimed.getFirst());
        assertThat(audited()).isOne();
        assertThat(documentOutbox.relay().runOnce().claimed()).as("leased: nobody else takes it").isZero();

        // Restart: a new relay, after the dead one's lease ran out.
        AuditTables.update("UPDATE document.outbox_event SET locked_until = now() - interval '1 second'");
        OutboxRelay restarted = new OutboxRelay("document-restarted", documentOutbox.store(),
                Map.of("audit.event.recorded", documentHandler), properties, Clock.systemUTC(), new SimpleMeterRegistry());
        assertThat(restarted.runOnce().delivered()).isOne();

        assertThat(pending("document")).isZero();
        assertThat(audited()).as("exactly one row").isOne();
    }

    @Test
    void crash_betweenCommitAndRelay_isDeliveredByTheNextRelay() {
        // Committed, then the process died before any relay ran: the row waits in the module's own schema.
        qaDb.inTransaction(status -> {
            qaAudit.conversationDeletedInTransaction(UUID.randomUUID());
            return null;
        });
        assertThat(pending("qa")).isOne();
        assertThat(audited()).isZero();

        // Restart: the relay's next poll finds the committed row and delivers it.
        assertThat(qaOutbox.relay().runOnce().delivered()).isOne();
        assertThat(pending("qa")).isZero();
        assertThat(audited()).isOne();
        assertThat(AuditTables.strings("SELECT actor_sub FROM audit.event")).containsExactly(sub);
    }

    @Test
    void ageGauge_whenARowWaits_reportsItsAgeUntilDelivered() {
        documentTransaction.executeWithoutResult(status -> documentAudit.append(uploaded(UUID.randomUUID())));
        AuditTables.update("UPDATE document.outbox_event SET created_at = now() - interval '120 seconds'");

        documentOutbox.relay().refreshGauges();
        assertThat(age("document")).isGreaterThanOrEqualTo(119.0);

        documentOutbox.relay().runOnce();
        assertThat(age("document")).isZero();
        assertThat(meters.get("outbox.oldest.pending.age").tag("outbox", "document").gauge().getId().getBaseUnit())
                .as("exported as outbox_oldest_pending_age_seconds").isEqualTo("seconds");
    }

    private double age(String outbox) {
        return meters.get("outbox.oldest.pending.age").tag("outbox", outbox).gauge().value();
    }

    private static AuditEvent uploaded(UUID documentId) {
        return AuditEvent.success(AuditAction.DOCUMENT_UPLOADED, AuditTargetType.DOCUMENT, documentId);
    }

    private static long pending(String schema) {
        return AuditTables.count("SELECT count(*) FROM " + schema + ".outbox_event");
    }

    private static long audited() {
        return AuditTables.count("SELECT count(*) FROM audit.event");
    }
}
