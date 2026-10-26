package com.verso.qa.service;

import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeCardState;
import com.verso.qa.api.dto.KnowledgeEvalRunResponse;
import com.verso.qa.api.dto.KnowledgeStaleReason;
import com.verso.qa.config.QaKnowledgeProperties;
import com.verso.qa.config.QaSkillProperties;
import com.verso.qa.repository.SkillRepository;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeEvalRepository;
import com.verso.qa.repository.KnowledgeEvalRepository.GoldenCase;
import com.verso.qa.repository.KnowledgeEvalRepository.Totals;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.KnowledgeCardRepository.Binding;
import com.verso.qa.service.KnowledgeCardRules.BindingState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The per-customer golden-set check (ADR-0049): every active golden case (a card that was verified) is checked for
 * source drift (do its bound chunks still exist with the same hashes?) and for retrieval recall@k (does hybrid search,
 * as the audience of its cited documents and with the card's stored question vector, still return a bound chunk in the
 * top k?). No model call: the vector is stored, the hashes come from the database. A case whose sources changed
 * also demotes its live card to STALE (SOURCE_CHANGED), the safety net behind the document outbox.
 *
 * <p>Scheduled here (not in the worker package: the manual run of the admin API shares this code, and the qa layers let
 * only controllers reach services): {@link #nightly()} at {@code verso.qa.knowledge.eval.cron} on every instance, and
 * the gauges every {@code verso.qa.knowledge.gauge-interval}. One failure never stops the next run (reference 23.4).
 *
 * <p>Multi-instance: the run row is the lock (one RUNNING row in the installation, with a lease the run renews);
 * an instance that died leaves a run whose lease ends, and the next claimer marks it FAILED.
 *
 * <p>Gauges (refreshed from the database, the same on every instance): {@code verso.qa.knowledge.cards{state}},
 * {@code verso.qa.knowledge.eval.recall}, {@code verso.qa.knowledge.eval.source.drift},
 * {@code verso.qa.knowledge.eval.alert} (alert KnowledgeRecallDropped).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeEval {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeEval.class);
    /** Cases between two lease renewals. */
    private static final int RENEW_EVERY = 50;

    private final QaDatabase db;
    private final KnowledgeEvalRepository runs;
    private final KnowledgeCardRepository cards;
    private final DocumentProvenance provenance;
    private final KnowledgeTransitions transitions;
    private final QaAudit audit;
    private final QaKnowledgeProperties properties;
    private final Clock clock;
    private final HookTriggers hooks;
    private final SkillEval skillEval;
    private final SkillRepository skills;
    private final QaSkillProperties skillProperties;
    private final Map<KnowledgeCardState, AtomicReference<Double>> cardCounts = new EnumMap<>(KnowledgeCardState.class);
    private final AtomicReference<Double> recall = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> drift = new AtomicReference<>(Double.NaN);
    private final AtomicReference<Double> alert = new AtomicReference<>(0.0);

    public KnowledgeEval(QaDatabase db, KnowledgeEvalRepository runs, KnowledgeCardRepository cards,
                         DocumentProvenance provenance, KnowledgeTransitions transitions, QaAudit audit,
                         QaKnowledgeProperties properties, Clock clock, MeterRegistry registry, HookTriggers hooks,
                         SkillEval skillEval, SkillRepository skills, QaSkillProperties skillProperties) {
        this.skillEval = skillEval;
        this.skills = skills;
        this.skillProperties = skillProperties;
        this.db = db;
        this.runs = runs;
        this.cards = cards;
        this.provenance = provenance;
        this.transitions = transitions;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
        this.hooks = hooks;
        for (KnowledgeCardState state : KnowledgeCardState.values()) {
            AtomicReference<Double> value = new AtomicReference<>(0.0);
            cardCounts.put(state, value);
            Gauge.builder("verso.qa.knowledge.cards", value, AtomicReference::get).tag("state", state.name().toLowerCase(java.util.Locale.ROOT))
                    .description("Verified answer cards by state (ADR-0049)").register(registry);
        }
        Gauge.builder("verso.qa.knowledge.eval.recall", recall, AtomicReference::get)
                .description("recall@k of the latest successful golden-set check").register(registry);
        Gauge.builder("verso.qa.knowledge.eval.source.drift", drift, AtomicReference::get)
                .description("Share of golden cases whose bound chunks changed, latest successful check").register(registry);
        Gauge.builder("verso.qa.knowledge.eval.alert", alert, AtomicReference::get)
                .description("1 when the latest successful golden-set check raised its alert").register(registry);
    }

    /**
     * One run. Empty when another run holds the lock (or, for SCHEDULED, today's run exists already).
     *
     * @param trigger SCHEDULED, MANUAL or HOOK (a hook rule asked, ADR-0052)
     */
    public Optional<KnowledgeEvalRunResponse> run(String trigger) {
        Instant now = clock.instant();
        LocalDate day = LocalDate.ofInstant(now, ZoneId.of(properties.eval().zone()));
        UUID token = UUID.randomUUID();
        Optional<UUID> claimed = db.inTransaction(status -> {
            runs.expireLeases(now);
            Optional<UUID> id = runs.claim(trigger, day, properties.eval().topK(), token,
                    now.plus(properties.eval().lease()), now);
            id.ifPresent(runId -> audit.evalRunInTransaction(runId, trigger));
            return id;
        });
        if (claimed.isEmpty()) {
            log.info("Golden-set check skipped: trigger={} reason=ALREADY_RUNNING_OR_DONE", trigger);
            return Optional.empty();
        }
        UUID runId = claimed.get();
        try {
            Totals totals = evaluate(runId, token);
            // ADR-0053: the APPROVED skills' golden cases in the same run, counted apart from the cards.
            SkillEval.Counts skillCounts = skillEval.evaluate(runId, properties.eval().topK(),
                    properties.eval().maxCases(),
                    () -> runs.renew(runId, token, clock.instant().plus(properties.eval().lease())));
            if (!db.inTransaction(status -> runs.storeSkillCounts(runId, token, skillCounts.cases(),
                    skillCounts.found(), skillCounts.missed(), skillCounts.sourceLost())
                    && finished(runId, token, totals))) {
                log.warn("Golden-set check result not stored: runId={} reason=LEASE_LOST", runId);
            }
            log.info("Golden-set check finished: runId={} trigger={} cases={} found={} missed={} sourceLost={} skipped={} "
                            + "recall={} baseline={} drift={} alert={} skillCases={} skillFound={} skillMissed={} "
                            + "skillSourceLost={}", runId, trigger, totals.cases(), totals.found(),
                    totals.missed(), totals.sourceLost(), totals.skipped(), totals.recall(), totals.baseline(),
                    totals.drift(), totals.alert(), skillCounts.cases(), skillCounts.found(), skillCounts.missed(),
                    skillCounts.sourceLost());
        } catch (RuntimeException e) {
            // Type only: a driver message could quote a question (llm-rules 2.1).
            log.error("Golden-set check failed: runId={} exceptionType={}", runId, e.getClass().getSimpleName());
            db.inTransaction(status -> {
                runs.fail(runId, token, clock.instant());
                return null;
            });
        }
        refreshGauges();
        return runs.find(runId);
    }

    /** Stores the result; an alert also tells the hook rules (GOLDEN_RECALL_DROP), in the same transaction. */
    private boolean finished(UUID runId, UUID token, Totals totals) {
        boolean stored = runs.finish(runId, token, totals, clock.instant());
        if (stored && totals.alert()) hooks.appendInTransaction(HookTrigger.GOLDEN_RECALL_DROP, "EVAL_RUN", runId, null);
        return stored;
    }

    private Totals evaluate(UUID runId, UUID token) {
        int topK = properties.eval().topK();
        List<GoldenCase> cases = runs.activeCases(properties.eval().maxCases());
        int found = 0;
        int missed = 0;
        int lost = 0;
        int skipped = 0;
        for (int i = 0; i < cases.size(); i++) {
            if (i > 0 && i % RENEW_EVERY == 0 && !runs.renew(runId, token, clock.instant().plus(properties.eval().lease()))) {
                throw new IllegalStateException("lease lost");
            }
            GoldenCase golden = cases.get(i);
            String outcome;
            Integer rank = null;
            List<Binding> bindings = cards.bindings(golden.cardId());
            Set<SourcePage> pages = new LinkedHashSet<>();
            bindings.forEach(b -> pages.add(b.page()));
            List<SourceChunk> current = pages.isEmpty() ? List.of() : provenance.sourceHashes(pages);
            if (KnowledgeCardRules.check(bindings, current) == BindingState.BROKEN) {
                outcome = "SOURCE_LOST";
                lost++;
                // The safety net behind the document outbox: a live card whose text is gone is demoted (never promoted).
                db.inTransaction(status -> transitions.markStaleInTransaction(golden.cardId(),
                        KnowledgeStaleReason.SOURCE_CHANGED));
            } else {
                Optional<float[]> vector = cards.embedding(golden.cardId());
                Set<UUID> documents = new LinkedHashSet<>();
                pages.forEach(p -> documents.add(p.documentId()));
                List<UUID> ranked = vector.isEmpty() ? List.of() : provenance.rankForAudience(documents,
                        golden.question(), new QuestionEmbedding(golden.embeddingModel(), vector.get()), topK);
                if (ranked.isEmpty()) {
                    outcome = "SKIPPED";
                    skipped++;
                } else {
                    Set<UUID> wanted = KnowledgeCardRules.currentChunkIds(bindings, current);
                    for (int position = 0; position < ranked.size(); position++) {
                        if (wanted.contains(ranked.get(position))) {
                            rank = position + 1;
                            break;
                        }
                    }
                    if (rank != null) {
                        outcome = "FOUND";
                        found++;
                    } else {
                        outcome = "MISSED";
                        missed++;
                    }
                }
            }
            runs.recordCase(runId, golden.cardId(), outcome, rank);
        }
        return totals(runId, cases.size(), found, missed, lost, skipped);
    }

    /** recall@k, drift and the alert decision (ADR-0049); package-private for the unit test. */
    Totals totals(UUID runId, int cases, int found, int missed, int lost, int skipped) {
        Double recallAtK = found + missed == 0 ? null : (double) found / (found + missed);
        Double sourceDrift = cases == 0 ? null : (double) lost / cases;
        Double baseline = runs.baseline(runId).orElse(null);
        return new Totals(cases, found, missed, lost, skipped, recallAtK, baseline, sourceDrift,
                alert(recallAtK, baseline, sourceDrift, properties.eval()));
    }

    /** The alert: recall fell by more than alert-drop against the baseline, or the drift passed its limit. */
    public static boolean alert(Double recallAtK, Double baseline, Double sourceDrift, QaKnowledgeProperties.Eval eval) {
        boolean dropped = recallAtK != null && baseline != null && baseline - recallAtK > eval.alertDrop() + 1e-9;
        boolean drifted = sourceDrift != null && sourceDrift > eval.alertSourceDrift() + 1e-9;
        return dropped || drifted;
    }

    /** Reads the gauges' values from the database (every instance shows the installation's state). */
    public void refreshGauges() {
        cardCounts.values().forEach(v -> v.set(0.0));
        for (Object[] row : cards.countsByState()) {
            cardCounts.get(KnowledgeCardState.valueOf((String) row[0])).set(((Long) row[1]).doubleValue());
        }
        Optional<KnowledgeEvalRunResponse> latest = runs.latestSucceeded();
        recall.set(latest.map(KnowledgeEvalRunResponse::recallAtK).orElse(Double.NaN));
        drift.set(latest.map(KnowledgeEvalRunResponse::sourceDrift).orElse(Double.NaN));
        alert.set(latest.map(r -> r.alert() ? 1.0 : 0.0).orElse(0.0));
    }

    /** The nightly work: housekeeping, then the check; logs carry the exception type only. */
    @Scheduled(cron = "${verso.qa.knowledge.eval.cron:0 30 2 * * *}", zone = "${verso.qa.knowledge.eval.zone:Europe/Istanbul}")
    public void nightly() {
        if (!properties.eval().enabled()) return;
        try {
            housekeeping();
            run("SCHEDULED");
        } catch (RuntimeException e) {
            log.error("Golden-set check failed: trigger=SCHEDULED exceptionType={}", e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${verso.qa.knowledge.gauge-interval:PT5M}", initialDelayString = "PT1M")
    public void gauges() {
        try {
            refreshGauges();
        } catch (RuntimeException e) {
            log.warn("Answer memory gauges not refreshed: exceptionType={}", e.getClass().getSimpleName());
        }
    }

    /** The runs, newest first. */
    public PageResponse<KnowledgeEvalRunResponse> runs(int page, int size) {
        return PageResponse.of(runs.list(page, size), page, size, runs.count());
    }

    /** Housekeeping before a scheduled run: expired verifications, old archived cards, old runs. */
    public void housekeeping() {
        int expired = 0;
        for (int i = 0; i < 20; i++) {
            int batch = transitions.expireDue(200);
            expired += batch;
            if (batch < 200) break;
        }
        Instant now = clock.instant();
        int purgedCards = db.inTransaction(status -> cards.purgeArchived(now.minus(properties.archivedRetention()), 500));
        int purgedRuns = db.inTransaction(status -> runs.purge(now.minus(properties.eval().runRetention()), 500));
        // ADR-0053: archived skills follow the cards' rule (their own retention).
        int purgedSkills = db.inTransaction(status ->
                skills.purgeArchived(now.minus(skillProperties.archivedRetention()), 500));
        if (expired + purgedCards + purgedRuns + purgedSkills > 0) {
            log.info("Answer memory housekeeping: expired={} archivedDeleted={} runsDeleted={} archivedSkillsDeleted={}",
                    expired, purgedCards, purgedRuns, purgedSkills);
        }
    }
}
