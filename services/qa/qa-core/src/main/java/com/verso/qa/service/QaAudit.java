package com.verso.qa.service;

import com.verso.audit.api.AuditTrail;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditRecord;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.FeedbackRequest;
import com.verso.qa.outbox.QaOutbox;
import com.verso.qa.repository.QaDatabase;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * What the qa module tells the audit log (ADR-0026). Ids, the outcome type, the rating and the model only: no
 * question, answer, title or file name ever reaches an {@link AuditEvent} (llm-rules 2.1).
 *
 * <p><b>Durable path (ADR-0039).</b> The record goes into the module's outbox {@code qa.outbox_event} in the same
 * transaction as the change it reports (the stored turn, the deleted conversations, the feedback), so it commits or
 * rolls back with it; the relay stores it in the audit log afterwards. The methods ending in {@code InTransaction}
 * must run inside {@link QaDatabase#inTransaction} (the store refuses otherwise). A metrics view has no change of its
 * own and gets a transaction of its own.
 *
 * <p><b>Direct path.</b> A question whose turn could not be stored has no transaction to join (the qa database
 * failed): its event is published the old way, after the answer, to the audit module's recorder.
 *
 * <p>Capturing who did it never fails the operation: a failure is logged with the exception type and counted as
 * {@code verso.audit.events.not.stored{reason="capture_failed"}} (alert AuditEventNotStored). The outbox insert is part
 * of the transaction: if it fails, the change rolls back with it.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaAudit {

    private static final Logger log = LoggerFactory.getLogger(QaAudit.class);

    private final QaOutbox outbox;
    private final QaDatabase db;
    private final AuditTrail trail;
    private final JsonMapper json;
    private final ApplicationEventPublisher events;
    private final Counter captureFailed;
    private final Counter writeFailed;

    public QaAudit(QaOutbox outbox, QaDatabase db, AuditTrail trail, JsonMapper json, ApplicationEventPublisher events,
                   MeterRegistry registry) {
        this.outbox = outbox;
        this.db = db;
        this.trail = trail;
        this.json = json;
        this.events = events;
        this.captureFailed = Counter.builder("verso.audit.events.not.stored").tag("reason", "capture_failed")
                .description("Audit events that could not be stored").register(registry);
        this.writeFailed = Counter.builder("verso.audit.events.not.stored").tag("reason", "failed")
                .description("Audit events that could not be stored").register(registry);
    }

    /** The stored turn of an answered question; in the transaction that stored it. */
    public void questionStoredInTransaction(UUID conversationId, UUID turnId, AnswerResponse answer) {
        append(() -> questionEvent(turnId, conversationId, answer));
    }

    /**
     * After the answer: only a question whose turn was not stored is published here (direct path, no transaction to
     * join); a stored one is already in the outbox.
     */
    public void questionAsked(AnswerResponse answer) {
        if (answer.turnId() != null) return;
        try {
            events.publishEvent(questionEvent(null, answer.conversationId(), answer));
        } catch (RuntimeException e) {
            log.error("Audit event not published: code=AUDIT_PUBLISH_FAILED exceptionType={}", e.getClass().getSimpleName());
        }
    }

    public void conversationDeletedInTransaction(UUID conversationId) {
        append(() -> AuditEvent.success(AuditAction.CONVERSATION_DELETED, AuditTargetType.CONVERSATION, conversationId));
    }

    public void allConversationsDeletedInTransaction() {
        append(() -> AuditEvent.success(AuditAction.CONVERSATIONS_DELETED_ALL, AuditTargetType.CONVERSATION));
    }

    public void feedbackGivenInTransaction(UUID conversationId, UUID turnId, FeedbackRequest request) {
        append(() -> AuditEvent.success(AuditAction.FEEDBACK_GIVEN, AuditTargetType.TURN, turnId)
                .inConversation(conversationId).withDetail(request.rating().name()));
    }

    /**
     * A read without a change of its own: one short transaction for the outbox row. It never fails the read (as
     * before, ADR-0026): a failed write is logged and counted as {@code reason="failed"}.
     */
    public void metricsViewed() {
        try {
            db.inTransaction(status -> {
                append(() -> AuditEvent.success(AuditAction.ADMIN_METRICS_VIEWED, AuditTargetType.METRICS));
                return null;
            });
        } catch (RuntimeException e) {
            writeFailed.increment();
            log.error("Audit event not stored: code=AUDIT_WRITE_FAILED reason=OUTBOX action=ADMIN_METRICS_VIEWED "
                    + "exceptionType={}", e.getClass().getSimpleName());
        }
    }

    private static AuditEvent questionEvent(UUID turnId, UUID conversationId, AnswerResponse answer) {
        List<UUID> cited = answer.citations().stream().map(Citation::documentId).toList();
        return AuditEvent.success(AuditAction.QUESTION_ASKED, AuditTargetType.TURN, turnId)
                .inConversation(conversationId).withDetail(answer.outcome().name())
                .answeredBy(answer.model(), answer.mode(), cited);
    }

    private void append(Supplier<AuditEvent> build) {
        AuditEvent event = null;
        AuditRecord record;
        String payload;
        try {
            event = build.get();
            record = trail.capture(event);
            payload = json.writeValueAsString(record);
        } catch (RuntimeException e) {
            captureFailed.increment();
            log.error("Audit event not captured: code=AUDIT_CAPTURE_FAILED action={} exceptionType={}",
                    event == null ? "UNKNOWN" : event.action(), e.getClass().getSimpleName());
            return;
        }
        outbox.store().append(record.eventId(), AuditTrail.OUTBOX_AGGREGATE_TYPE,
                event.targetId() != null ? event.targetId() : record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
        // Delivered right after the commit when it can be (the poll is the safety net).
        outbox.relay().deliverAfterCommit(record.eventId(), AuditTrail.OUTBOX_EVENT_TYPE, payload);
    }
}
