package com.verso.qa.service;

import com.verso.qa.api.dto.HookAction;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.config.QaDiagnosticsProperties;
import com.verso.qa.outbox.HookEvent;
import com.verso.qa.outbox.QaHookOutboxHandler;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.HookRepository;
import com.verso.qa.repository.HookRepository.NewNotification;
import com.verso.qa.repository.HookRepository.Rule;
import com.verso.qa.repository.QaDatabase;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The hook rule engine (ADR-0048 principle 5, ADR-0052): an event (from the qa outbox, the document outbox or a
 * scheduled check) runs every enabled rule of its trigger. A rule does exactly one of the four allowed things:
 *
 * <ul>
 *   <li>{@link HookAction#NOTIFY} / {@link HookAction#FLAG}: one in-app notification for the admins;</li>
 *   <li>{@link HookAction#DEMOTE}: the live cards bound to a contradiction's chunks go STALE (CONTRADICTION), through
 *       the same demote-only transition as every other staleness (KnowledgeTransitions);</li>
 *   <li>{@link HookAction#RUN_EVAL}: asks for the golden-set check, which runs once after a quiet period.</li>
 * </ul>
 *
 * There is no path to promote, verify, archive, delete or change access: the engine holds no such dependency, the
 * action enum has no such value, and the database refuses any other action. Every action writes its audit row (ids
 * and the rule key only) in its own transaction; every action is idempotent (a notification per rule and event id,
 * a demotion only of a live card, one pending eval request), so a redelivered event changes nothing.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class HookEngine implements QaHookOutboxHandler.Consumer {

    private static final Logger log = LoggerFactory.getLogger(HookEngine.class);

    private final QaDatabase db;
    private final HookRepository hooks;
    private final ContradictionRepository contradictions;
    private final KnowledgeTransitions transitions;
    private final ObjectProvider<KnowledgeEval> eval;
    private final QaAudit audit;
    private final QaDiagnosticsProperties properties;
    private final Clock clock;

    public HookEngine(QaDatabase db, HookRepository hooks, ContradictionRepository contradictions,
                      KnowledgeTransitions transitions, ObjectProvider<KnowledgeEval> eval, QaAudit audit,
                      QaDiagnosticsProperties properties, Clock clock) {
        this.db = db;
        this.hooks = hooks;
        this.contradictions = contradictions;
        this.transitions = transitions;
        this.eval = eval;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Runs the enabled rules of the event's trigger. A failing action is thrown after the others ran: the outbox
     * delivers the event again, and the actions that already happened change nothing the second time.
     */
    @Override
    public void handle(HookEvent event) {
        RuntimeException failure = null;
        int acted = 0;
        for (Rule rule : hooks.enabledRules(event.trigger())) {
            try {
                if (apply(rule, event)) acted++;
            } catch (RuntimeException e) {
                log.error("Hook action failed: rule={} trigger={} subjectType={} exceptionType={}", rule.key(),
                        event.trigger(), event.subjectType(), e.getClass().getSimpleName());
                failure = e;
            }
        }
        if (acted > 0) {
            log.info("Hook rules acted: trigger={} subjectType={} subjectId={} actions={}", event.trigger(),
                    event.subjectType(), event.subjectId(), acted);
        }
        if (failure != null) throw failure;
    }

    /** One rule on one event; true when something changed. Package-private for the unit test. */
    boolean apply(Rule rule, HookEvent event) {
        return switch (rule.action()) {
            case NOTIFY, FLAG -> notify(rule, event);
            case DEMOTE -> demote(rule, event);
            case RUN_EVAL -> requestEval(rule, event);
        };
    }

    private boolean notify(Rule rule, HookEvent event) {
        Instant now = clock.instant();
        NewNotification notification = new NewNotification(rule.key(), rule.action(), event.trigger(),
                event.subjectType(), event.subjectId(), event.detail(), severity(event.trigger()),
                rule.key() + ":" + event.id());
        Boolean created = db.inTransaction(status -> {
            Optional<UUID> id = hooks.insertNotification(notification, now);
            id.ifPresent(ignored -> audit.hookFiredInTransaction(event.subjectType(), event.subjectId(), rule.key()));
            return id.isPresent();
        });
        return Boolean.TRUE.equals(created);
    }

    /**
     * Only a contradiction demotes, and only the live cards bound to its chunks; a card already stale or archived is
     * left as it is (the transition is conditional).
     */
    private boolean demote(Rule rule, HookEvent event) {
        if (!"CONTRADICTION".equals(event.subjectType())) return false;
        Optional<ContradictionRepository.Candidate> candidate = contradictions.find(event.subjectId());
        if (candidate.isEmpty()) return false;
        int demoted = 0;
        for (UUID cardId : contradictions.liveCardsBoundTo(candidate.get().first().chunkId(),
                candidate.get().second().chunkId())) {
            Boolean changed = db.inTransaction(status -> {
                boolean stale = transitions.markStaleInTransaction(cardId, KnowledgeStaleReason.CONTRADICTION);
                if (stale) audit.hookFiredInTransaction("KNOWLEDGE_CARD", cardId, rule.key());
                return stale;
            });
            if (Boolean.TRUE.equals(changed)) demoted++;
        }
        return demoted > 0;
    }

    /** A document change asks for the golden-set check only when a golden case cites the document. */
    private boolean requestEval(Rule rule, HookEvent event) {
        if ("DOCUMENT".equals(event.subjectType()) && !hooks.goldenCaseCites(event.subjectId())) return false;
        Instant now = clock.instant();
        db.inTransaction(status -> {
            hooks.requestEval(rule.key(), now);
            audit.hookFiredInTransaction(event.subjectType(), event.subjectId(), rule.key());
            return null;
        });
        return true;
    }

    /**
     * Runs a requested golden-set check after the quiet period (one instance takes the request). When another check
     * holds the lock, the request is put back and taken again on a later tick.
     */
    @Scheduled(fixedDelayString = "${verso.qa.diagnostics.eval-poll:PT1M}", initialDelayString = "PT2M")
    public void runRequestedEval() {
        try {
            Instant now = clock.instant();
            Optional<String> rule = db.inTransaction(status -> hooks.takeEvalRequest(now.minus(properties.evalDebounce())));
            if (rule.isEmpty()) return;
            if (eval.getObject().run("HOOK").isEmpty()) {
                db.inTransaction(status -> {
                    hooks.requestEval(rule.get(), now);
                    return null;
                });
            }
        } catch (RuntimeException e) {
            log.error("Hook eval request failed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    /** How urgent a notification of this trigger is. */
    static KnowledgeHealthSeverity severity(HookTrigger trigger) {
        return switch (trigger) {
            case GOLDEN_RECALL_DROP -> KnowledgeHealthSeverity.CRITICAL;
            case CARD_DISPUTED, CONTRADICTION_FOUND, CLUSTER_GROWING -> KnowledgeHealthSeverity.WARNING;
            case DOCUMENT_CHANGED, CLUSTER_EMERGED, CARD_STALE, VERIFICATION_DUE -> KnowledgeHealthSeverity.INFO;
        };
    }
}
