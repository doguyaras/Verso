package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.verso.qa.api.dto.HookAction;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.config.QaDiagnosticsProperties;
import com.verso.qa.outbox.HookEvent;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Candidate;
import com.verso.qa.repository.ContradictionRepository.Side;
import com.verso.qa.repository.HookRepository;
import com.verso.qa.repository.HookRepository.NewNotification;
import com.verso.qa.repository.HookRepository.Rule;
import com.verso.qa.repository.QaDatabase;
import java.lang.reflect.Constructor;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/**
 * ADR-0048 principle 5, ADR-0052: hooks only notify, flag, demote or run the golden-set check; never promote, verify,
 * archive, delete or change access. Each action is idempotent and audited by id.
 */
class HookEngineTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final UUID SUBJECT = UUID.fromString("0190a000-0000-7000-8000-000000000001");
    private static final UUID CARD = UUID.fromString("0190a000-0000-7000-8000-000000000002");

    private final QaDatabase db = mock(QaDatabase.class);
    private final HookRepository hooks = mock(HookRepository.class);
    private final ContradictionRepository contradictions = mock(ContradictionRepository.class);
    private final KnowledgeTransitions transitions = mock(KnowledgeTransitions.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<KnowledgeEval> eval = mock(ObjectProvider.class);
    private final QaAudit audit = mock(QaAudit.class);
    private HookEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        when(db.inTransaction(any())).thenAnswer(call -> ((Function<Object, Object>) call.getArgument(0)).apply(null));
        engine = new HookEngine(db, hooks, contradictions, transitions, eval, audit, properties(),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void actions_areExactlyNotifyFlagDemoteAndRunEval() {
        assertThat(Arrays.stream(HookAction.values()).map(Enum::name))
                .containsExactly("NOTIFY", "FLAG", "DEMOTE", "RUN_EVAL");
        for (String forbidden : List.of("PROMOTE", "VERIFY", "ARCHIVE", "DELETE", "GRANT", "REVOKE")) {
            assertThatThrownBy(() -> HookAction.valueOf(forbidden)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void engine_holdsNoDependencyThatCouldPromoteDeleteOrChangeAccess() {
        Constructor<?> constructor = HookEngine.class.getConstructors()[0];
        List<String> types = Arrays.stream(constructor.getParameterTypes()).map(Class::getSimpleName).toList();
        assertThat(types).doesNotContain("KnowledgeCardService", "KnowledgeCardRepository", "DocumentIngestion",
                "DocumentCatalog", "ConversationService");
        assertThat(types).allMatch(t -> !t.contains("Collection") && !t.contains("Subject"));
    }

    @Test
    void notify_storesOneNotificationPerRuleAndEvent_andAuditsIdsOnly() {
        Rule rule = rule("CARD_STALE_NOTIFY", HookTrigger.CARD_STALE, HookAction.NOTIFY);
        when(hooks.enabledRules(HookTrigger.CARD_STALE)).thenReturn(List.of(rule));
        HookEvent event = new HookEvent(UUID.randomUUID(), HookTrigger.CARD_STALE, "KNOWLEDGE_CARD", CARD, "EXPIRED");
        when(hooks.insertNotification(any(), eq(NOW))).thenReturn(Optional.of(UUID.randomUUID()), Optional.empty());

        engine.handle(event);
        engine.handle(event);

        ArgumentCaptor<NewNotification> stored = ArgumentCaptor.forClass(NewNotification.class);
        verify(hooks, org.mockito.Mockito.times(2)).insertNotification(stored.capture(), eq(NOW));
        assertThat(stored.getAllValues()).extracting(NewNotification::dedupeKey).containsOnly("CARD_STALE_NOTIFY:" + event.id());
        assertThat(stored.getValue().severity()).isEqualTo(KnowledgeHealthSeverity.INFO);
        // The redelivery found the dedupe key taken: one audit row, not two.
        verify(audit, org.mockito.Mockito.times(1)).hookFiredInTransaction("KNOWLEDGE_CARD", CARD, "CARD_STALE_NOTIFY");
        verifyNoInteractions(transitions);
    }

    @Test
    void demote_onlyEverMarksLiveCardsStale_andOnlyForAContradiction() {
        Rule rule = rule("CONTRADICTION_DEMOTE_CARD", HookTrigger.CONTRADICTION_FOUND, HookAction.DEMOTE);
        UUID chunkA = UUID.randomUUID();
        UUID chunkB = UUID.randomUUID();
        when(contradictions.find(SUBJECT)).thenReturn(Optional.of(new Candidate(SUBJECT,
                new Side(chunkA, UUID.randomUUID(), 1, "a".repeat(64)), new Side(chunkB, UUID.randomUUID(), 2, "b".repeat(64)),
                0.95, 1, NOW, NOW)));
        when(contradictions.liveCardsBoundTo(chunkA, chunkB)).thenReturn(List.of(CARD));
        when(transitions.markStaleInTransaction(CARD, KnowledgeStaleReason.CONTRADICTION)).thenReturn(true, false);

        HookEvent event = new HookEvent(UUID.randomUUID(), HookTrigger.CONTRADICTION_FOUND, "CONTRADICTION", SUBJECT, null);
        assertThat(engine.apply(rule, event)).isTrue();
        assertThat(engine.apply(rule, event)).as("already stale: nothing changes").isFalse();
        verify(audit, org.mockito.Mockito.times(1)).hookFiredInTransaction("KNOWLEDGE_CARD", CARD, rule.key());

        // A DEMOTE rule on any other subject does nothing at all.
        assertThat(engine.apply(rule, new HookEvent(UUID.randomUUID(), HookTrigger.CONTRADICTION_FOUND, "KNOWLEDGE_CARD",
                CARD, null))).isFalse();
        verify(transitions, org.mockito.Mockito.times(2)).markStaleInTransaction(any(), any());
    }

    @Test
    void runEval_isRequestedOnlyWhenAGoldenCaseCitesTheChangedDocument() {
        Rule rule = rule("DOCUMENT_CHANGED_RUN_EVAL", HookTrigger.DOCUMENT_CHANGED, HookAction.RUN_EVAL);
        UUID cited = UUID.randomUUID();
        when(hooks.goldenCaseCites(cited)).thenReturn(true);
        assertThat(engine.apply(rule, new HookEvent(UUID.randomUUID(), HookTrigger.DOCUMENT_CHANGED, "DOCUMENT", SUBJECT,
                "DELETED"))).isFalse();
        assertThat(engine.apply(rule, new HookEvent(UUID.randomUUID(), HookTrigger.DOCUMENT_CHANGED, "DOCUMENT", cited,
                "REPLACED"))).isTrue();
        verify(hooks).requestEval("DOCUMENT_CHANGED_RUN_EVAL", NOW);
        verify(audit).hookFiredInTransaction("DOCUMENT", cited, "DOCUMENT_CHANGED_RUN_EVAL");
        // The check itself runs later, on the scheduler (coalesced), not inside the event.
        verifyNoInteractions(eval);
    }

    @Test
    void handle_whenOneActionFails_runsTheOthersAndThrowsForRedelivery() {
        Rule failing = rule("CONTRADICTION_FLAG", HookTrigger.CONTRADICTION_FOUND, HookAction.FLAG);
        Rule demote = rule("CONTRADICTION_DEMOTE_CARD", HookTrigger.CONTRADICTION_FOUND, HookAction.DEMOTE);
        when(hooks.enabledRules(HookTrigger.CONTRADICTION_FOUND)).thenReturn(List.of(failing, demote));
        when(hooks.insertNotification(any(), any())).thenThrow(new IllegalStateException("db"));
        when(contradictions.find(SUBJECT)).thenReturn(Optional.empty());
        HookEvent event = new HookEvent(UUID.randomUUID(), HookTrigger.CONTRADICTION_FOUND, "CONTRADICTION", SUBJECT, null);
        assertThatThrownBy(() -> engine.handle(event)).isInstanceOf(IllegalStateException.class);
        verify(contradictions).find(SUBJECT);
        verify(audit, never()).hookFiredInTransaction(anyString(), any(), anyString());
    }

    @Test
    void severity_isCriticalForARecallDrop_andAWarningForDisputesAndContradictions() {
        assertThat(HookEngine.severity(HookTrigger.GOLDEN_RECALL_DROP)).isEqualTo(KnowledgeHealthSeverity.CRITICAL);
        assertThat(HookEngine.severity(HookTrigger.CARD_DISPUTED)).isEqualTo(KnowledgeHealthSeverity.WARNING);
        assertThat(HookEngine.severity(HookTrigger.CONTRADICTION_FOUND)).isEqualTo(KnowledgeHealthSeverity.WARNING);
        assertThat(HookEngine.severity(HookTrigger.CARD_STALE)).isEqualTo(KnowledgeHealthSeverity.INFO);
    }

    private static Rule rule(String key, HookTrigger trigger, HookAction action) {
        return new Rule(key, trigger, action, true, true, NOW);
    }

    private static QaDiagnosticsProperties properties() {
        return new QaDiagnosticsProperties(true, "0 30 3 * * *", "Europe/Istanbul", Duration.ofDays(30), 5, 0.8, 0.85,
                5000, 2000, 5, 3, Duration.ofDays(180), Duration.ofDays(14), Duration.ofHours(1), Duration.ofDays(400),
                Duration.ofDays(180), Duration.ofDays(30), 400, Duration.ofMinutes(10),
                new QaDiagnosticsProperties.Contradiction(true, 0.9, 2000, 3, 50));
    }
}
