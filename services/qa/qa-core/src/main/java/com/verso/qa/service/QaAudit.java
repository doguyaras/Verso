package com.verso.qa.service;

import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.qa.api.dto.AnswerResponse;
import com.verso.qa.api.dto.Citation;
import com.verso.qa.api.dto.FeedbackRequest;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * What the qa module tells the audit log (ADR-0026), after the operation committed. Ids, the outcome type, the rating
 * and the model only: no question, answer, title or file name ever reaches an {@link AuditEvent} (llm-rules 2.1). Any
 * problem building or publishing an event is swallowed here with a fixed log line: the audit log must never turn a
 * successful request into a failed one.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class QaAudit {

    private static final Logger log = LoggerFactory.getLogger(QaAudit.class);

    private final ApplicationEventPublisher events;

    public QaAudit(ApplicationEventPublisher events) {
        this.events = events;
    }

    /** The turn id is null when the history could not store the turn; the question was still asked. */
    public void questionAsked(AnswerResponse answer) {
        publish(() -> AuditEvent.success(AuditAction.QUESTION_ASKED, AuditTargetType.TURN, answer.turnId())
                .inConversation(answer.conversationId()).withDetail(answer.outcome().name())
                .answeredBy(answer.model(), answer.mode(), answer.citations().stream().map(Citation::documentId).toList()));
    }

    public void conversationDeleted(UUID conversationId) {
        publish(() -> AuditEvent.success(AuditAction.CONVERSATION_DELETED, AuditTargetType.CONVERSATION, conversationId));
    }

    public void allConversationsDeleted() {
        publish(() -> AuditEvent.success(AuditAction.CONVERSATIONS_DELETED_ALL, AuditTargetType.CONVERSATION));
    }

    public void feedbackGiven(UUID conversationId, UUID turnId, FeedbackRequest request) {
        publish(() -> AuditEvent.success(AuditAction.FEEDBACK_GIVEN, AuditTargetType.TURN, turnId)
                .inConversation(conversationId).withDetail(request.rating().name()));
    }

    public void metricsViewed() {
        publish(() -> AuditEvent.success(AuditAction.ADMIN_METRICS_VIEWED, AuditTargetType.METRICS));
    }

    private void publish(java.util.function.Supplier<AuditEvent> event) {
        try {
            events.publishEvent(event.get());
        } catch (RuntimeException e) {
            log.error("Audit event not published: code=AUDIT_PUBLISH_FAILED exceptionType={}", e.getClass().getSimpleName());
        }
    }
}
