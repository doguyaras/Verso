package com.verso.qa.service;

import com.verso.document.api.DocumentDiagnostics;
import com.verso.document.api.DocumentRetrieval;
import com.verso.document.api.dto.ChunkPairScan;
import com.verso.document.api.dto.PageResponse;
import com.verso.document.api.dto.QuestionEmbedding;
import com.verso.document.api.dto.SharedDocument;
import com.verso.document.api.dto.SourceChunk;
import com.verso.platform.core.exception.ServiceException;
import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthRunResponse;
import com.verso.qa.config.QaDiagnosticsProperties;
import com.verso.qa.config.VersoAiProperties;
import com.verso.qa.outbox.HookEvent;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Side;
import com.verso.qa.repository.HookRepository;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.ClusterRow;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.CollectionUse;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.DocumentCitations;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.Totals;
import com.verso.qa.repository.QaDatabase;
import com.verso.qa.repository.UsageRepository;
import com.verso.qa.service.QuestionClusterer.Cluster;
import com.verso.qa.service.QuestionClusterer.Point;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * The diagnostics job of the learning knowledge layer (ADR-0048 phase 2, ADR-0052): a scheduled batch, never on the
 * hot path. One run over a window of uncertain questions (NOT_FOUND, UNCITED, grounding-flagged):
 *
 * <ol>
 *   <li>embeds the questions not embedded before (local model, bounded per run) and clusters them (no LLM);</li>
 *   <li>keeps clusters of at least k (&ge; 5) distinct askers, with k-anonymous keywords and representative hashes, and
 *       their trend against the previous run;</li>
 *   <li>snapshots stale and never-cited shared documents, coverage per collection, card counts and down-rating reasons
 *       per collection (k-anonymous cells, ids and counts only);</li>
 *   <li>scans a slice of shared chunks for contradiction candidates (numbers or codes that differ, AnchorCheck);</li>
 *   <li>stores it all with the run's SUCCEEDED state in one transaction, with the hook events of new and growing
 *       clusters and new contradictions (qa outbox).</li>
 * </ol>
 *
 * <p>Multi-instance: the run row is the lock (one RUNNING row, claim token, lease), like the golden-set check. Logs
 * carry counts and ids only; question and chunk texts stay in memory for the embedding and the comparison.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeDiagnostics.class);
    /** Embeddings between two lease renewals. */
    private static final int RENEW_EVERY = 50;
    /** Differences kept per candidate. */
    private static final int MAX_DIFFERENCES = 5;
    private static final int PURGE_BATCH = 500;

    private final QaDatabase db;
    private final KnowledgeDiagnosticsRepository runs;
    private final ContradictionRepository contradictions;
    private final KnowledgeCardRepository cards;
    private final UsageRepository usage;
    private final HookRepository hooks;
    private final DocumentRetrieval retrieval;
    private final DocumentDiagnostics documents;
    private final HookTriggers triggers;
    private final HookEngine engine;
    private final QaAudit audit;
    private final QaDiagnosticsProperties properties;
    private final VersoAiProperties ai;
    private final JsonMapper json;
    private final Clock clock;

    public KnowledgeDiagnostics(QaDatabase db, KnowledgeDiagnosticsRepository runs, ContradictionRepository contradictions,
                                KnowledgeCardRepository cards, UsageRepository usage,
                                HookRepository hooks, DocumentRetrieval retrieval,
                                DocumentDiagnostics documents, HookTriggers triggers, HookEngine engine, QaAudit audit,
                                QaDiagnosticsProperties properties, VersoAiProperties ai, JsonMapper json, Clock clock) {
        this.db = db;
        this.runs = runs;
        this.contradictions = contradictions;
        this.cards = cards;
        this.usage = usage;
        this.hooks = hooks;
        this.retrieval = retrieval;
        this.documents = documents;
        this.triggers = triggers;
        this.engine = engine;
        this.audit = audit;
        this.properties = properties;
        this.ai = ai;
        this.json = json;
        this.clock = clock;
    }

    /** A cluster ready to store. */
    private record Draft(Cluster cluster, int askers, List<String> keywords, List<byte[]> hashes) {
    }

    /** A contradiction candidate ready to store. */
    private record Found(Side first, Side second, double similarity, int differences) {
    }

    /**
     * One run. Empty when another run holds the lock (or, for SCHEDULED, today's run exists already).
     *
     * @param trigger SCHEDULED or MANUAL
     */
    public Optional<KnowledgeHealthRunResponse> run(String trigger) {
        Instant now = clock.instant();
        LocalDate today = LocalDate.ofInstant(now, properties.zoneId());
        LocalDate from = today.minusDays(properties.window().toDays() - 1);
        int k = properties.minAskers();
        UUID token = UUID.randomUUID();
        Optional<UUID> claimed = db.inTransaction(status -> {
            runs.expireLeases(now);
            Optional<UUID> id = runs.claim(trigger, today, from, today, k, token, now.plus(properties.lease()), now);
            id.ifPresent(runId -> audit.diagnosticsRunInTransaction(runId, trigger));
            return id;
        });
        if (claimed.isEmpty()) {
            log.info("Knowledge diagnostics skipped: trigger={} reason=ALREADY_RUNNING_OR_DONE", trigger);
            return Optional.empty();
        }
        UUID runId = claimed.get();
        try {
            diagnose(runId, token, from, today, k);
        } catch (RuntimeException e) {
            // Type only: a driver message could quote a question (llm-rules 2.1).
            log.error("Knowledge diagnostics failed: runId={} exceptionType={}", runId, e.getClass().getSimpleName());
            db.inTransaction(status -> {
                runs.fail(runId, token, clock.instant());
                return null;
            });
        }
        return runs.find(runId);
    }

    private void diagnose(UUID runId, UUID token, LocalDate from, LocalDate to, int k) {
        String model = ai.embeddingModel();
        List<UUID> uncertain = runs.uncertainTurns(from, to, properties.maxQuestions());
        Map<UUID, float[]> vectors = new LinkedHashMap<>(runs.vectors(uncertain, model));
        int embedded = embedMissing(runId, token, uncertain, vectors, model);
        List<Point> points = new ArrayList<>();
        vectors.forEach((turn, vector) -> points.add(new Point(turn, vector)));
        List<Draft> drafts = new ArrayList<>();
        for (Cluster cluster : QuestionClusterer.cluster(points, properties.clusterThreshold())) {
            // k-anonymity: a cluster smaller than k turns cannot have k askers; the count itself is the database's.
            if (cluster.size() < k) continue;
            int askers = runs.askers(cluster.turnIds());
            if (!DiagnosticsRules.kAnonymous(askers, k)) continue;
            drafts.add(new Draft(cluster, askers, runs.keywords(cluster.turnIds(), k, properties.keywords()),
                    runs.representativeHashes(cluster.turnIds(), k, properties.representatives())));
        }
        List<ClusterRow> previous = runs.previousSucceeded(runId).map(runs::clusters).orElse(List.of());
        List<Integer> matches = DiagnosticsRules.matchPrevious(drafts.stream().map(d -> d.cluster().centroid()).toList(),
                previous.stream().map(ClusterRow::centroid).toList(), properties.trendThreshold());
        DiagnosticSnapshot snapshot = snapshot(from, to, k);
        UUID cursor = runs.lastCursor().orElse(null);
        List<Found> found = new ArrayList<>();
        int scanned = 0;
        if (properties.contradiction().enabled()) {
            ChunkPairScan scan = documents.similarChunkPairs(cursor, properties.contradiction().maxChunks(),
                    properties.contradiction().neighbours(), properties.contradiction().similarity());
            scanned = scan.scanned();
            cursor = scan.cursor();
            found = contradictions(scan);
        }
        String summary = json.writeValueAsString(snapshot);
        Totals totals = new Totals(uncertain.size(), embedded, drafts.size(), scanned, cursor, summary);
        List<Found> candidates = found;
        Boolean stored = db.inTransaction(status -> {
            if (!runs.holds(runId, token)) return false;
            Instant now = clock.instant();
            for (int i = 0; i < drafts.size(); i++) {
                Draft draft = drafts.get(i);
                Integer previousTurns = matches.get(i) < 0 ? null : previous.get(matches.get(i)).turns();
                ClusterTrend trend = DiagnosticsRules.trend(draft.cluster().size(), previousTurns);
                UUID clusterId = runs.insertCluster(runId, i, draft.cluster().centroid(), model, draft.cluster().size(),
                        draft.askers(), previousTurns, trend, draft.keywords(), draft.hashes());
                if (trend == ClusterTrend.NEW) {
                    triggers.appendInTransaction(HookTrigger.CLUSTER_EMERGED, "QUESTION_CLUSTER", clusterId, null);
                } else if (trend == ClusterTrend.GROWING) {
                    triggers.appendInTransaction(HookTrigger.CLUSTER_GROWING, "QUESTION_CLUSTER", clusterId, null);
                }
            }
            for (Found candidate : candidates) {
                ContradictionRepository.Upserted row = contradictions.upsert(candidate.first(), candidate.second(),
                        candidate.similarity(), candidate.differences(), now);
                if (row.created()) {
                    triggers.appendInTransaction(HookTrigger.CONTRADICTION_FOUND, "CONTRADICTION", row.id(), null);
                }
            }
            if (!runs.finish(runId, token, totals, now)) {
                status.setRollbackOnly();
                return false;
            }
            return true;
        });
        if (!Boolean.TRUE.equals(stored)) {
            log.warn("Knowledge diagnostics result not stored: runId={} reason=LEASE_LOST", runId);
            return;
        }
        log.info("Knowledge diagnostics finished: runId={} window={}..{} questions={} embedded={} clusters={} "
                        + "staleDocuments={} contradictionsScanned={} contradictionCandidates={}", runId, from, to,
                uncertain.size(), embedded, drafts.size(), snapshot.staleDocuments().size(), scanned, candidates.size());
        verificationDue();
        housekeeping();
    }

    /**
     * Embeds questions without a vector of the current model (at most embed-per-run; the rest wait for the next run).
     * The embedding model being down ends the embedding for this run, not the run.
     */
    private int embedMissing(UUID runId, UUID token, List<UUID> uncertain, Map<UUID, float[]> vectors, String model) {
        List<UUID> missing = uncertain.stream().filter(id -> !vectors.containsKey(id))
                .limit(properties.embedPerRun()).toList();
        if (missing.isEmpty()) return 0;
        Map<UUID, String> questions = runs.questions(missing);
        int embedded = 0;
        for (Map.Entry<UUID, String> question : questions.entrySet()) {
            if (embedded > 0 && embedded % RENEW_EVERY == 0
                    && !runs.renew(runId, token, clock.instant().plus(properties.lease()))) {
                throw new IllegalStateException("lease lost");
            }
            QuestionEmbedding embedding;
            try {
                embedding = retrieval.embed(question.getValue());
            } catch (ServiceException e) {
                log.warn("Knowledge diagnostics embedding stopped: runId={} embedded={} code={}", runId, embedded,
                        e.getErrorCode().getCode());
                break;
            }
            if (!model.equals(embedding.model())) break;
            float[] vector = embedding.vector();
            db.inTransaction(status -> {
                runs.storeVector(question.getKey(), vector, embedding.model(), clock.instant());
                return null;
            });
            vectors.put(question.getKey(), vector);
            embedded++;
        }
        return embedded;
    }

    /** The run's k-anonymous aggregates of shared documents, cards and ratings. */
    private DiagnosticSnapshot snapshot(LocalDate from, LocalDate to, int k) {
        Instant now = clock.instant();
        List<SharedDocument> shared = documents.sharedDocuments(DocumentDiagnostics.MAX_DOCUMENTS);
        Map<UUID, UUID> collectionOf = new LinkedHashMap<>();
        Map<UUID, SharedDocument> byId = new HashMap<>();
        for (SharedDocument document : shared) {
            collectionOf.put(document.id(), document.collectionId());
            byId.put(document.id(), document);
        }
        // Stale: old, yet cited by at least k distinct accounts in the window.
        Instant staleBefore = now.minus(properties.staleAfter());
        List<DocumentCitations> cited = runs.citedBy(from, to, k, DocumentDiagnostics.MAX_DOCUMENTS);
        List<DocumentCitations> old = cited.stream().filter(c -> byId.containsKey(c.documentId())
                && byId.get(c.documentId()).createdAt().isBefore(staleBefore)).toList();
        Map<UUID, Integer> staleCards = runs.staleCardsCiting(old.stream().map(DocumentCitations::documentId).toList());
        List<DiagnosticSnapshot.StaleDocument> stale = old.stream().map(c -> new DiagnosticSnapshot.StaleDocument(
                c.documentId(), collectionOf.get(c.documentId()), byId.get(c.documentId()).createdAt(), c.citations(),
                c.askers(), staleCards.getOrDefault(c.documentId(), 0))).toList();
        // Coverage per collection; the window's share is suppressed below k askers.
        Set<UUID> citedEver = new HashSet<>(usage.citedEver(DocumentDiagnostics.MAX_DOCUMENTS));
        Map<UUID, CollectionUse> use = new HashMap<>();
        for (CollectionUse row : runs.collectionUse(from, to, collectionOf)) use.put(row.collectionId(), row);
        Map<UUID, int[]> perCollection = new LinkedHashMap<>();
        for (SharedDocument document : shared) {
            int[] counts = perCollection.computeIfAbsent(document.collectionId(), c -> new int[2]);
            counts[0]++;
            if (!citedEver.contains(document.id())) counts[1]++;
        }
        List<DiagnosticSnapshot.Coverage> coverage = new ArrayList<>();
        long neverCited = 0;
        for (Map.Entry<UUID, int[]> entry : perCollection.entrySet()) {
            CollectionUse row = use.get(entry.getKey());
            Integer citedInWindow = row != null && DiagnosticsRules.kAnonymous(row.askers(), k) ? row.citedDocuments() : null;
            if (row == null) citedInWindow = 0;
            coverage.add(new DiagnosticSnapshot.Coverage(entry.getKey(), entry.getValue()[0], citedInWindow,
                    entry.getValue()[1]));
            neverCited += entry.getValue()[1];
        }
        Map<String, Long> states = new HashMap<>();
        for (Object[] row : cards.countsByState()) states.put((String) row[0], (Long) row[1]);
        DiagnosticSnapshot.Cards cardCounts = new DiagnosticSnapshot.Cards(states.getOrDefault("VERIFIED", 0L),
                states.getOrDefault("DRAFT", 0L), states.getOrDefault("STALE", 0L), states.getOrDefault("DISPUTED", 0L),
                states.getOrDefault("ARCHIVED", 0L),
                runs.countVerifiedExpiringBy(now, now.plus(properties.verificationDueWithin())));
        List<DiagnosticSnapshot.Feedback> feedback = runs.downRatings(from, to, collectionOf, k).stream()
                .map(cell -> new DiagnosticSnapshot.Feedback(cell.collectionId(), cell.reason(), cell.ratings(),
                        cell.askers()))
                .toList();
        return new DiagnosticSnapshot(stale, coverage, neverCited, cardCounts, feedback);
    }

    /** The pairs whose numbers or codes differ (AnchorCheck folding); each unordered pair once, at most max-pairs. */
    private List<Found> contradictions(ChunkPairScan scan) {
        List<Found> found = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ChunkPairScan.Pair pair : scan.pairs()) {
            if (found.size() >= properties.contradiction().maxPairs()) break;
            SourceChunk a = pair.first();
            SourceChunk b = pair.second();
            String key = a.chunkId().compareTo(b.chunkId()) < 0 ? a.chunkId() + "|" + b.chunkId()
                    : b.chunkId() + "|" + a.chunkId();
            if (!seen.add(key) || a.content() == null || b.content() == null) continue;
            List<AnchorCheck.Difference> differences = AnchorCheck.differences(a.content(), b.content(), MAX_DIFFERENCES);
            if (differences.isEmpty()) continue;
            found.add(new Found(new Side(a.chunkId(), a.documentId(), a.pageNumber(), a.contentSha256()),
                    new Side(b.chunkId(), b.documentId(), b.pageNumber(), b.contentSha256()), pair.similarity(),
                    differences.size()));
        }
        return found;
    }

    /**
     * The scheduled VERIFICATION_DUE check: one event per card and expiry (the event id is derived from both, so the
     * nightly repetition adds nothing).
     */
    private void verificationDue() {
        Instant now = clock.instant();
        for (Map.Entry<UUID, Instant> card : runs.verifiedExpiring(now, now.plus(properties.verificationDueWithin()), 200)
                .entrySet()) {
            UUID eventId = UUID.nameUUIDFromBytes(("verso:verification-due:" + card.getKey() + ":"
                    + card.getValue().toEpochMilli()).getBytes(StandardCharsets.UTF_8));
            try {
                engine.handle(new HookEvent(eventId, HookTrigger.VERIFICATION_DUE, "KNOWLEDGE_CARD", card.getKey(), null));
            } catch (RuntimeException e) {
                log.warn("Verification due hook failed: cardId={} exceptionType={}", card.getKey(),
                        e.getClass().getSimpleName());
            }
        }
    }

    /** Retention of runs (with clusters), notifications and unseen contradiction candidates. */
    public void housekeeping() {
        Instant now = clock.instant();
        int purgedRuns = db.inTransaction(status -> runs.purge(now.minus(properties.runRetention()), PURGE_BATCH));
        int purgedNotifications = db.inTransaction(status ->
                hooks.purgeNotifications(now.minus(properties.notificationRetention()), PURGE_BATCH));
        int purgedCandidates = db.inTransaction(status ->
                contradictions.purgeUnseen(now.minus(properties.contradictionRetention()), PURGE_BATCH));
        if (purgedRuns + purgedNotifications + purgedCandidates > 0) {
            log.info("Knowledge diagnostics housekeeping: runsDeleted={} notificationsDeleted={} candidatesDeleted={}",
                    purgedRuns, purgedNotifications, purgedCandidates);
        }
    }

    /** The nightly run; one failure never stops the next run (reference 23.4). */
    @Scheduled(cron = "${verso.qa.diagnostics.cron:0 30 3 * * *}", zone = "${verso.qa.diagnostics.zone:Europe/Istanbul}")
    public void nightly() {
        if (!properties.enabled()) return;
        try {
            run("SCHEDULED");
        } catch (RuntimeException e) {
            log.error("Knowledge diagnostics failed: trigger=SCHEDULED exceptionType={}", e.getClass().getSimpleName());
        }
    }

    /** The runs, newest first. */
    public PageResponse<KnowledgeHealthRunResponse> runs(int page, int size) {
        return PageResponse.of(runs.list(page, size), page, size, runs.count());
    }
}
