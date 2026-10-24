package com.verso.privacy.service.impl;

import com.verso.audit.api.AuditSubjects;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditSubjectEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.DocumentSubjects;
import com.verso.document.api.dto.SubjectDocument;
import com.verso.platform.security.authority.VersoRoles;
import com.verso.privacy.api.dto.AnswerRedactionRequest;
import com.verso.privacy.api.dto.AnswerRedactionResponse;
import com.verso.privacy.api.dto.SubjectDeletionRequest;
import com.verso.privacy.api.dto.SubjectDeletionResponse;
import com.verso.privacy.api.dto.SubjectDocumentItem;
import com.verso.privacy.api.dto.SubjectDocuments;
import com.verso.privacy.api.dto.SubjectHistory;
import com.verso.privacy.api.dto.SubjectSummaryResponse;
import com.verso.privacy.exception.PrivacyErrorCode;
import com.verso.privacy.exception.PrivacyServiceException;
import com.verso.privacy.service.SubjectIds;
import com.verso.privacy.service.SubjectRequestService;
import com.verso.qa.api.QaSubjects;
import com.verso.qa.api.dto.SubjectConversation;
import com.verso.qa.api.dto.SubjectHistoryCounts;
import java.io.IOException;
import java.io.OutputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import tools.jackson.core.JsonGenerator;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@link SubjectRequestService} (ADR-0034). The erasure is a sequence of local transactions, each in one module's
 * schema (operation-consistency 1): no step spans two schemas, and every step is idempotent, so the request itself is
 * the recovery. A failure between steps leaves a partial erasure; repeating the same request finishes it and reports
 * only what it removed the second time (see the runbook). No saga table: nothing is reserved remotely and nothing is
 * ever compensated, an erasure only moves forward.
 *
 * <p>Order: history first (the subject's own words), then for each batch of personal documents the answers of
 * <em>other</em> accounts that cited them are redacted before the documents go, so an interruption never leaves an
 * answer quoting a document whose id is no longer known. The audit row is written last, only for a complete erasure.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SubjectRequestServiceImpl implements SubjectRequestService {

    private static final Logger log = LoggerFactory.getLogger(SubjectRequestServiceImpl.class);
    static final String EXPORT_FORMAT = "verso-subject-export/1";

    private final QaSubjects qa;
    private final DocumentSubjects documents;
    private final DocumentCatalog catalog;
    private final AuditSubjects audit;
    private final ApplicationEventPublisher events;
    private final JsonMapper json;
    private final Clock clock;

    public SubjectRequestServiceImpl(QaSubjects qa, DocumentSubjects documents, DocumentCatalog catalog,
                                     AuditSubjects audit, ApplicationEventPublisher events, JsonMapper json, Clock clock) {
        this.qa = qa;
        this.documents = documents;
        this.catalog = catalog;
        this.audit = audit;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public SubjectSummaryResponse summary(String sub) {
        String subject = SubjectIds.require(sub);
        SubjectSummaryResponse summary = find(subject);
        events.publishEvent(AuditEvent.success(AuditAction.SUBJECT_VIEWED, AuditTargetType.SUBJECT,
                SubjectIds.auditRef(subject)));
        return summary;
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public ExportPlan planExport(String sub) {
        String subject = SubjectIds.require(sub);
        SubjectSummaryResponse summary = find(subject);
        boolean truncated = summary.history().conversations() > MAX_EXPORT_CONVERSATIONS
                || summary.auditEventsAsActor() > MAX_EXPORT_AUDIT_EVENTS || summary.documents().listsTruncated();
        // Recorded before a byte is sent, like the audit CSV export (ADR-0026): a broken download is still an access.
        events.publishEvent(AuditEvent.success(AuditAction.SUBJECT_EXPORTED, AuditTargetType.SUBJECT,
                SubjectIds.auditRef(subject)).withDetail(truncated ? "TRUNCATED" : "COMPLETE"));
        log.info("Subject request handled: operation=export outcome=started truncated={}", truncated);
        return new ExportPlan(subject, summary, truncated);
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public void writeExport(ExportPlan plan, OutputStream out) throws IOException {
        try (JsonGenerator g = json.createGenerator(out)) {
            g.writeStartObject();
            g.writeStringProperty("format", EXPORT_FORMAT);
            g.writeStringProperty("subject", plan.sub());
            g.writePOJOProperty("exportedAt", Instant.now(clock));
            g.writeBooleanProperty("truncated", plan.truncated());
            g.writePOJOProperty("history", plan.summary().history());
            g.writeNumberProperty("auditEventsAsActorCount", plan.summary().auditEventsAsActor());

            g.writeName("conversations");
            g.writeStartArray();
            UUID after = null;
            int written = 0;
            while (written < MAX_EXPORT_CONVERSATIONS) {
                int limit = Math.min(QaSubjects.MAX_CONVERSATION_BATCH, MAX_EXPORT_CONVERSATIONS - written);
                List<SubjectConversation> batch = qa.conversations(plan.sub(), after, limit);
                for (SubjectConversation conversation : batch) g.writePOJO(conversation);
                written += batch.size();
                if (batch.size() < limit) break;
                after = batch.getLast().id();
            }
            g.writeEndArray();

            // The document lists were read once for the summary; they are capped there (DocumentSubjects.MAX_LISTED).
            g.writePOJOProperty("documents", plan.summary().documents());

            g.writeName("auditEventsAsActor");
            g.writeStartArray();
            Instant afterAt = null;
            UUID afterId = null;
            int rows = 0;
            while (rows < MAX_EXPORT_AUDIT_EVENTS) {
                int limit = Math.min(AuditSubjects.MAX_BATCH, MAX_EXPORT_AUDIT_EVENTS - rows);
                List<AuditSubjectEvent> batch = audit.byActor(plan.sub(), afterAt, afterId, limit);
                for (AuditSubjectEvent event : batch) g.writePOJO(event);
                rows += batch.size();
                if (batch.size() < limit) break;
                afterAt = batch.getLast().occurredAt();
                afterId = batch.getLast().id();
            }
            g.writeEndArray();
            g.writeEndObject();
        }
        log.info("Subject request handled: operation=export outcome=written");
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public SubjectDeletionResponse delete(SubjectDeletionRequest request) {
        String subject = SubjectIds.require(request == null ? null : request.sub());
        if (!subject.equals(request.confirm())) {
            throw new PrivacyServiceException(PrivacyErrorCode.SUBJECT_CONFIRMATION_MISMATCH, "CONFIRMATION_MISMATCH");
        }
        String step = "history";
        try {
            SubjectHistoryCounts history = qa.deleteHistory(subject);
            long deleted = 0;
            long redacted = 0;
            while (true) {
                step = "documents";
                List<UUID> batch = documents.personalDocumentIds(subject, DocumentSubjects.MAX_DELETE_BATCH);
                if (batch.isEmpty()) break;
                step = "redaction";
                redacted += qa.redactAnswersCiting(batch);
                step = "documents";
                deleted += documents.deletePersonalDocuments(subject, batch).size();
            }
            step = "report";
            SubjectSummaryResponse left = find(subject);
            events.publishEvent(AuditEvent.success(AuditAction.SUBJECT_DELETED, AuditTargetType.SUBJECT,
                    SubjectIds.auditRef(subject)).withDetail(left.documents().collectionCount() > 0
                            ? "COLLECTION_DOCUMENTS_KEPT" : "COMPLETE"));
            log.info("Subject request handled: operation=delete outcome=deleted conversations={} turns={} "
                            + "personalDocuments={} answersRedacted={} collectionDocumentsKept={}",
                    history.conversations(), history.turns(), deleted, redacted, left.documents().collectionCount());
            return new SubjectDeletionResponse(history(history), deleted, redacted,
                    new SubjectDeletionResponse.Kept(left.documents(), left.auditEventsAsActor()));
        } catch (RuntimeException e) {
            // Every step is idempotent: the same request finishes the erasure (docs/runbooks/kvkk-basvurusu.md).
            log.warn("Subject deletion interrupted: step={} outcome=partial exceptionType={}", step,
                    e.getClass().getSimpleName());
            throw e;
        }
    }

    @Override
    @PreAuthorize(VersoRoles.HAS_ADMIN)
    public AnswerRedactionResponse redactAnswers(UUID documentId, AnswerRedactionRequest request) {
        if (request == null || !documentId.equals(request.confirm())) {
            throw new PrivacyServiceException(PrivacyErrorCode.REDACTION_CONFIRMATION_MISMATCH, "CONFIRMATION_MISMATCH");
        }
        // Only after the document is gone: while it exists the answer is still backed by a readable source.
        if (!catalog.describeAcrossAccounts(Set.of(documentId)).isEmpty()) {
            throw new PrivacyServiceException(PrivacyErrorCode.DOCUMENT_NOT_DELETED, "DOCUMENT_EXISTS");
        }
        int redacted = qa.redactAnswersCiting(List.of(documentId));
        events.publishEvent(AuditEvent.success(AuditAction.ANSWERS_REDACTED, AuditTargetType.DOCUMENT, documentId));
        log.info("Answers redacted: documentId={} count={} outcome=redacted", documentId, redacted);
        return new AnswerRedactionResponse(documentId, redacted);
    }

    private SubjectSummaryResponse find(String subject) {
        SubjectHistoryCounts history = qa.count(subject);
        com.verso.document.api.dto.SubjectDocuments held = documents.find(subject);
        return new SubjectSummaryResponse(history(history), documents(held), audit.countByActor(subject));
    }

    private static SubjectHistory history(SubjectHistoryCounts counts) {
        return new SubjectHistory(counts.conversations(), counts.turns(), counts.feedback(), counts.activeDays());
    }

    static SubjectDocuments documents(com.verso.document.api.dto.SubjectDocuments held) {
        boolean truncated = held.personalCount() > held.personal().size()
                || held.collectionCount() > held.collection().size()
                || held.grantedCollections().size() >= DocumentSubjects.MAX_LISTED
                || held.createdCollections().size() >= DocumentSubjects.MAX_LISTED;
        return new SubjectDocuments(held.personalCount(), held.personal().stream().map(SubjectRequestServiceImpl::item).toList(),
                held.collectionCount(), held.collection().stream().map(SubjectRequestServiceImpl::item).toList(),
                held.grantedCollections(), held.createdCollections(), truncated);
    }

    private static SubjectDocumentItem item(SubjectDocument d) {
        return new SubjectDocumentItem(d.id(), d.fileName(), d.format().name(), d.status().name(), d.sizeBytes(),
                d.collectionId(), d.createdAt());
    }
}
