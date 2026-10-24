package com.verso.privacy.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.verso.audit.api.AuditSubjects;
import com.verso.audit.api.dto.AuditEvent;
import com.verso.audit.api.dto.AuditSubjectEvent;
import com.verso.audit.api.enums.AuditAction;
import com.verso.audit.api.enums.AuditTargetType;
import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.DocumentSubjects;
import com.verso.document.api.dto.DocumentSummary;
import com.verso.document.api.dto.SubjectDocument;
import com.verso.document.api.dto.SubjectDocuments;
import com.verso.document.api.enums.DocumentFormat;
import com.verso.document.api.enums.DocumentStatus;
import com.verso.privacy.api.dto.AnswerRedactionRequest;
import com.verso.privacy.api.dto.SubjectDeletionRequest;
import com.verso.privacy.api.dto.SubjectDeletionResponse;
import com.verso.privacy.exception.PrivacyErrorCode;
import com.verso.privacy.exception.PrivacyServiceException;
import com.verso.privacy.service.SubjectIds;
import com.verso.privacy.service.SubjectRequestService.ExportPlan;
import com.verso.qa.api.QaSubjects;
import com.verso.qa.api.dto.SubjectConversation;
import com.verso.qa.api.dto.SubjectHistoryCounts;
import com.verso.qa.api.dto.SubjectTurn;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The coordinator's order, idempotency and refusals with the modules' contracts stubbed (ADR-0034). The real erasure
 * against PostgreSQL is SubjectRequestApiTest (verso-app, CI).
 */
class SubjectRequestServiceImplTest {

    private static final String SUB = "acct-subject-1";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final QaSubjects qa = mock(QaSubjects.class);
    private final DocumentSubjects documents = mock(DocumentSubjects.class);
    private final DocumentCatalog catalog = mock(DocumentCatalog.class);
    private final AuditSubjects audit = mock(AuditSubjects.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private SubjectRequestServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SubjectRequestServiceImpl(qa, documents, catalog, audit, events, JSON,
                Clock.fixed(Instant.parse("2026-10-24T10:00:00Z"), ZoneOffset.UTC));
        when(qa.count(SUB)).thenReturn(new SubjectHistoryCounts(1, 2, 1, 1));
        when(documents.find(SUB)).thenReturn(new SubjectDocuments(0, List.of(), 0, List.of(), List.of(), List.of()));
        when(audit.countByActor(SUB)).thenReturn(3L);
    }

    @Test
    void delete_whenConfirmationDiffers_deletesNothing() {
        for (SubjectDeletionRequest request : new SubjectDeletionRequest[]{new SubjectDeletionRequest(SUB, "acct-subject-2"),
                new SubjectDeletionRequest(SUB, SUB.toUpperCase()), new SubjectDeletionRequest(SUB, null)}) {
            assertThatThrownBy(() -> service.delete(request)).isInstanceOf(PrivacyServiceException.class)
                    .satisfies(e -> assertThat(((PrivacyServiceException) e).getErrorCode())
                            .isEqualTo(PrivacyErrorCode.SUBJECT_CONFIRMATION_MISMATCH));
        }
        verify(qa, never()).deleteHistory(any());
        verify(documents, never()).deletePersonalDocuments(any(), any());
        verify(events, never()).publishEvent(any(Object.class));
    }

    @Test
    void delete_whenSubIsMalformed_isRefusedBeforeAnyCall() {
        assertThatThrownBy(() -> service.delete(new SubjectDeletionRequest("a%", "a%")))
                .isInstanceOf(PrivacyServiceException.class);
        verify(qa, never()).deleteHistory(any());
    }

    /** History first; each document batch: answers of others redacted, then the documents; the audit row last. */
    @Test
    void delete_whenDocumentsExist_redactsBeforeDeletingAndAuditsOnce() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(qa.deleteHistory(SUB)).thenReturn(new SubjectHistoryCounts(2, 5, 1, 3));
        when(documents.personalDocumentIds(SUB, DocumentSubjects.MAX_DELETE_BATCH))
                .thenReturn(List.of(a, b)).thenReturn(List.of());
        when(qa.redactAnswersCiting(List.of(a, b))).thenReturn(1);
        when(documents.deletePersonalDocuments(SUB, List.of(a, b))).thenReturn(List.of(a, b));
        SubjectDocument kept = new SubjectDocument(UUID.randomUUID(), "ortak.pdf", DocumentFormat.PDF,
                DocumentStatus.READY, 10, UUID.randomUUID(), Instant.now());
        when(documents.find(SUB)).thenReturn(new SubjectDocuments(0, List.of(), 1, List.of(kept), List.of(), List.of()));

        SubjectDeletionResponse response = service.delete(new SubjectDeletionRequest(SUB, SUB));

        InOrder order = inOrder(qa, documents, events);
        order.verify(qa).deleteHistory(SUB);
        order.verify(qa).redactAnswersCiting(List.of(a, b));
        order.verify(documents).deletePersonalDocuments(SUB, List.of(a, b));
        order.verify(events).publishEvent(any(Object.class));
        assertThat(response.historyDeleted().conversations()).isEqualTo(2);
        assertThat(response.historyDeleted().activeDays()).isEqualTo(3);
        assertThat(response.personalDocumentsDeleted()).isEqualTo(2);
        assertThat(response.answersRedacted()).isEqualTo(1);
        assertThat(response.kept().documents().collectionCount()).isOne();
        assertThat(response.kept().auditEventsAsActor()).isEqualTo(3);
        AuditEvent event = published();
        assertThat(event.action()).isEqualTo(AuditAction.SUBJECT_DELETED);
        assertThat(event.targetType()).isEqualTo(AuditTargetType.SUBJECT);
        assertThat(event.targetId()).isEqualTo(SubjectIds.auditRef(SUB));
        assertThat(event.detailCode()).isEqualTo("COLLECTION_DOCUMENTS_KEPT");
    }

    /** A failed step propagates (503 upstream) and records no SUBJECT_DELETED: the same request finishes it later. */
    @Test
    void delete_whenAStepFails_stopsWithoutTheAuditRowAndCanBeRepeated() {
        UUID a = UUID.randomUUID();
        when(qa.deleteHistory(SUB)).thenReturn(SubjectHistoryCounts.NONE);
        when(documents.personalDocumentIds(SUB, DocumentSubjects.MAX_DELETE_BATCH)).thenReturn(List.of(a));
        when(qa.redactAnswersCiting(List.of(a))).thenThrow(new IllegalStateException("database gone"));

        assertThatThrownBy(() -> service.delete(new SubjectDeletionRequest(SUB, SUB)))
                .isInstanceOf(IllegalStateException.class);
        verify(documents, never()).deletePersonalDocuments(any(), any());
        verify(events, never()).publishEvent(any(Object.class));

        org.mockito.Mockito.doReturn(0).when(qa).redactAnswersCiting(List.of(a));
        when(documents.personalDocumentIds(SUB, DocumentSubjects.MAX_DELETE_BATCH))
                .thenReturn(List.of(a)).thenReturn(List.of());
        when(documents.deletePersonalDocuments(SUB, List.of(a))).thenReturn(List.of(a));
        assertThat(service.delete(new SubjectDeletionRequest(SUB, SUB)).personalDocumentsDeleted()).isOne();
    }

    @Test
    void redaction_whenTheDocumentStillExists_isRefusedWith409() {
        UUID document = UUID.randomUUID();
        when(catalog.describeAcrossAccounts(any())).thenReturn(List.of(new DocumentSummary(document, "x.pdf",
                DocumentFormat.PDF, Instant.now())));
        assertThatThrownBy(() -> service.redactAnswers(document, new AnswerRedactionRequest(document)))
                .satisfies(e -> assertThat(((PrivacyServiceException) e).getErrorCode())
                        .isEqualTo(PrivacyErrorCode.DOCUMENT_NOT_DELETED));
        assertThatThrownBy(() -> service.redactAnswers(document, new AnswerRedactionRequest(UUID.randomUUID())))
                .satisfies(e -> assertThat(((PrivacyServiceException) e).getErrorCode())
                        .isEqualTo(PrivacyErrorCode.REDACTION_CONFIRMATION_MISMATCH));
        verify(qa, never()).redactAnswersCiting(any());
    }

    @Test
    void redaction_whenTheDocumentIsGone_redactsAndAudits() {
        UUID document = UUID.randomUUID();
        when(catalog.describeAcrossAccounts(any())).thenReturn(List.of());
        when(qa.redactAnswersCiting(List.of(document))).thenReturn(4);
        assertThat(service.redactAnswers(document, new AnswerRedactionRequest(document)).answersRedacted()).isEqualTo(4);
        AuditEvent event = published();
        assertThat(event.action()).isEqualTo(AuditAction.ANSWERS_REDACTED);
        assertThat(event.targetId()).isEqualTo(document);
    }

    /** One JSON document, conversations in keyset batches, the subject's audit rows, the export audited up front. */
    @Test
    void export_whenWritten_isOneJsonDocumentWithEverySection() throws Exception {
        List<SubjectConversation> all = new ArrayList<>();
        for (int i = 0; i < QaSubjects.MAX_CONVERSATION_BATCH + 2; i++) {
            all.add(new SubjectConversation(UUID.randomUUID(), "konu " + i, Instant.now(), Instant.now(),
                    List.of(new SubjectTurn(UUID.randomUUID(), Instant.now(), "soru " + i, "cevap " + i, "ANSWERED",
                            "local", "m", List.of(), null))));
        }
        when(qa.conversations(eq(SUB), eq(null), anyInt())).thenReturn(all.subList(0, QaSubjects.MAX_CONVERSATION_BATCH));
        when(qa.conversations(eq(SUB), eq(all.get(QaSubjects.MAX_CONVERSATION_BATCH - 1).id()), anyInt()))
                .thenReturn(all.subList(QaSubjects.MAX_CONVERSATION_BATCH, all.size()));
        when(audit.byActor(eq(SUB), any(), any(), anyInt())).thenReturn(List.of(new AuditSubjectEvent(UUID.randomUUID(),
                Instant.now(), "QUESTION_ASKED", "SUCCESS", "TURN", UUID.randomUUID())));

        ExportPlan plan = service.planExport(SUB);
        assertThat(published().action()).isEqualTo(AuditAction.SUBJECT_EXPORTED);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        service.writeExport(plan, out);

        JsonNode root = JSON.readTree(out.toString(StandardCharsets.UTF_8));
        assertThat(root.get("format").asString()).isEqualTo(SubjectRequestServiceImpl.EXPORT_FORMAT);
        assertThat(root.get("subject").asString()).isEqualTo(SUB);
        assertThat(root.get("truncated").asBoolean()).isFalse();
        assertThat(root.get("conversations")).hasSize(all.size());
        assertThat(root.get("conversations").get(0).get("turns").get(0).get("question").asString()).isEqualTo("soru 0");
        assertThat(root.get("auditEventsAsActor")).hasSize(1);
        assertThat(root.get("auditEventsAsActor").get(0).has("actor")).isFalse();
        assertThat(root.get("documents").get("personalCount").asLong()).isZero();
        assertThat(root.get("history").get("turns").asLong()).isEqualTo(2);
    }

    /** A cap that cuts the export is known before the first byte: plan, header and audit detail all say so. */
    @Test
    void planExport_whenACapIsExceeded_isMarkedTruncatedUpFront() {
        when(qa.count(SUB)).thenReturn(new SubjectHistoryCounts(
                com.verso.privacy.service.SubjectRequestService.MAX_EXPORT_CONVERSATIONS + 1, 0, 0, 0));
        assertThat(service.planExport(SUB).truncated()).isTrue();
        assertThat(published().detailCode()).isEqualTo("TRUNCATED");

        when(qa.count(SUB)).thenReturn(SubjectHistoryCounts.NONE);
        when(audit.countByActor(SUB)).thenReturn(
                (long) com.verso.privacy.service.SubjectRequestService.MAX_EXPORT_AUDIT_EVENTS + 1);
        assertThat(service.planExport(SUB).truncated()).isTrue();

        when(audit.countByActor(SUB)).thenReturn(0L);
        assertThat(service.planExport(SUB).truncated()).isFalse();
        assertThat(published().detailCode()).isEqualTo("COMPLETE");
    }

    private AuditEvent published() {
        ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
        verify(events, org.mockito.Mockito.atLeastOnce()).publishEvent(captor.capture());
        return (AuditEvent) captor.getValue();
    }
}
