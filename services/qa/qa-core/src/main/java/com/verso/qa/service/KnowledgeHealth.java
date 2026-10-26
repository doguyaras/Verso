package com.verso.qa.service;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.DocumentDiagnostics;
import com.verso.document.api.DocumentProvenance;
import com.verso.document.api.DocumentSystemModel;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.Reader;
import com.verso.document.api.dto.SourceChunk;
import com.verso.document.api.dto.SourcePage;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.KnowledgeHealthFormat;
import com.verso.qa.api.dto.KnowledgeHealthReport;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cards;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cluster;
import com.verso.qa.api.dto.KnowledgeHealthReport.CollectionCoverage;
import com.verso.qa.api.dto.KnowledgeHealthReport.Contradiction;
import com.verso.qa.api.dto.KnowledgeHealthReport.Difference;
import com.verso.qa.api.dto.KnowledgeHealthReport.FeedbackCell;
import com.verso.qa.api.dto.KnowledgeHealthReport.RunPoint;
import com.verso.qa.api.dto.KnowledgeHealthReport.Side;
import com.verso.qa.api.dto.KnowledgeHealthReport.StaleDocument;
import com.verso.qa.config.QaDiagnosticsProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.ContradictionRepository;
import com.verso.qa.repository.ContradictionRepository.Candidate;
import com.verso.qa.repository.HookRepository;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.ClusterRow;
import com.verso.qa.repository.KnowledgeDiagnosticsRepository.RunRow;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Builds the knowledge-health report (ADR-0052) for one admin from the latest diagnostics run of the range. What the
 * admin sees is filtered here, with the admin's own principals (ADR-0028):
 *
 * <ul>
 *   <li>question texts only under the installation's question-text policy (VERSO_SHOW_UNANSWERED_QUESTIONS), and only
 *       the representative questions the run found k-anonymous;</li>
 *   <li>a stale document's name only when the admin may read it;</li>
 *   <li>a contradiction's side named only when the admin may read it, and the differing values only when the admin may
 *       read both sides (the values come from both texts); otherwise ids and pages.</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KnowledgeHealth {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeHealth.class);

    /** Contradiction candidates per report (the most recently seen). */
    static final int CONTRADICTIONS = 50;
    /** System-model signals per kind and report (ADR-0054). */
    static final int SYSTEM_MODEL_SIGNALS = 20;
    private static final int TREND_POINTS = 120;
    private static final int MAX_DIFFERENCES = 5;

    private final KnowledgeDiagnosticsRepository runs;
    private final ContradictionRepository contradictions;
    private final HookRepository hooks;
    private final KnowledgeCardRepository cards;
    private final DocumentCatalog catalog;
    private final DocumentDiagnostics documents;
    private final DocumentProvenance provenance;
    private final DocumentSystemModel systemModel;
    private final QaDiagnosticsProperties properties;
    private final UsageMetricsProperties policy;
    private final JsonMapper json;
    private final Clock clock;

    public KnowledgeHealth(KnowledgeDiagnosticsRepository runs, ContradictionRepository contradictions,
                           HookRepository hooks, KnowledgeCardRepository cards,
                           DocumentCatalog catalog, DocumentDiagnostics documents, DocumentProvenance provenance,
                           DocumentSystemModel systemModel, QaDiagnosticsProperties properties,
                           UsageMetricsProperties policy, JsonMapper json, Clock clock) {
        this.systemModel = systemModel;
        this.runs = runs;
        this.contradictions = contradictions;
        this.hooks = hooks;
        this.cards = cards;
        this.catalog = catalog;
        this.documents = documents;
        this.provenance = provenance;
        this.properties = properties;
        this.policy = policy;
        this.json = json;
        this.clock = clock;
    }

    /** The range check of every report request: from..to inclusive, at most max-range-days. */
    public void checkRange(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > properties.maxRangeDays()) {
            log.warn("Knowledge health rejected: code={} reason=RANGE_INVALID",
                    QaErrorCode.KNOWLEDGE_HEALTH_RANGE_INVALID.getCode());
            throw new QaServiceException(QaErrorCode.KNOWLEDGE_HEALTH_RANGE_INVALID, "RANGE_INVALID");
        }
    }

    /** The report as the file of the consulting team: Markdown for people, JSON for tools (same filtering). */
    public byte[] export(Reader admin, LocalDate from, LocalDate to, KnowledgeHealthFormat format) {
        KnowledgeHealthReport report = report(admin, from, to);
        return format == KnowledgeHealthFormat.JSON ? json.writeValueAsBytes(report)
                : KnowledgeHealthMarkdown.render(report, properties.zoneId()).getBytes(StandardCharsets.UTF_8);
    }

    public KnowledgeHealthReport report(Reader admin, LocalDate from, LocalDate to) {
        checkRange(from, to);
        Instant now = clock.instant();
        boolean showQuestions = policy.showUnansweredQuestions();
        Cards cardCounts = cards(now);
        long openFlags = hooks.openFlags();
        List<RunPoint> trend = runs.series(from, to, TREND_POINTS).stream()
                .map(r -> new RunPoint((UUID) r[0], (LocalDate) r[1], (Integer) r[2], (Integer) r[3])).toList();
        Optional<RunRow> latest = runs.latestSucceeded(from, to);
        List<KnowledgeHealthReport.SystemModelSignal> structural = systemModelSignals(admin);
        if (latest.isEmpty()) {
            return new KnowledgeHealthReport(from, to, now, null, properties.minAskers(), showQuestions,
                    withSystemModel(KnowledgeHealthFindings.of(null, properties.minAskers(), List.of(), List.of(),
                            properties.staleAfter(), List.of(), 0, 0, cardCounts, List.of(), openFlags), structural),
                    List.of(), List.of(), List.of(), 0, List.of(), cardCounts, List.of(), trend, openFlags,
                    structural);
        }
        RunRow row = latest.get();
        DiagnosticSnapshot snapshot = row.summaryJson() == null ? new DiagnosticSnapshot(null, null, 0, null, null)
                : json.readValue(row.summaryJson(), DiagnosticSnapshot.class);
        int k = row.minAskers();
        KnowledgeHealthReport.Run run = new KnowledgeHealthReport.Run(row.id(), row.windowFrom(), row.windowTo(),
                row.finishedAt(), row.questions(), row.clusters(), row.contradictionsChecked());
        List<Cluster> clusters = clusters(row, showQuestions);
        Set<UUID> collectionIds = new LinkedHashSet<>();
        snapshot.staleDocuments().forEach(d -> collectionIds.add(d.collectionId()));
        snapshot.coverage().forEach(c -> collectionIds.add(c.collectionId()));
        snapshot.feedback().forEach(f -> collectionIds.add(f.collectionId()));
        collectionIds.remove(null);
        Map<UUID, String> collectionNames = documents.collectionNames(collectionIds);
        List<StaleDocument> stale = staleDocuments(admin, snapshot, collectionNames);
        List<CollectionCoverage> coverage = snapshot.coverage().stream().map(c -> new CollectionCoverage(
                c.collectionId(), collectionNames.get(c.collectionId()), c.documents(), c.citedInWindow(),
                c.neverCited(), c.citedInWindow() == null || c.documents() == 0 ? null
                        : Math.round(c.citedInWindow() * 10_000.0 / c.documents()) / 10_000.0)).toList();
        List<FeedbackCell> feedback = snapshot.feedback().stream().map(f -> new FeedbackCell(f.collectionId(),
                collectionNames.get(f.collectionId()), "NONE".equals(f.reason()) ? null : FeedbackReason.valueOf(f.reason()),
                f.ratings(), f.askers())).toList();
        List<Contradiction> found = contradictions(admin);
        return new KnowledgeHealthReport(from, to, now, run, k, showQuestions,
                withSystemModel(KnowledgeHealthFindings.of(run, k, clusters, stale, properties.staleAfter(), coverage,
                        snapshot.neverCited(), (int) contradictions.count(), cardCounts, feedback, openFlags),
                        structural),
                clusters, stale, coverage, snapshot.neverCited(), found, cardCounts, feedback, trend, openFlags,
                structural);
    }

    /**
     * The system model's signals (ADR-0054), live over the shared documents the admin may read (the document module
     * filters with the admin's principals). A failure leaves the rest of the report intact.
     */
    private List<KnowledgeHealthReport.SystemModelSignal> systemModelSignals(Reader admin) {
        try {
            return systemModel.signals(admin, SYSTEM_MODEL_SIGNALS).stream()
                    .map(s -> new KnowledgeHealthReport.SystemModelSignal(s.kind().name(), s.type().name(), s.key(),
                            s.label(), s.documentIds(), s.values()))
                    .toList();
        } catch (RuntimeException e) {
            log.error("Knowledge health system model signals failed: code=SYSTEM_MODEL_SIGNALS_FAILED exceptionType={}",
                    e.getClass().getSimpleName());
            return List.of();
        }
    }

    private static List<KnowledgeHealthReport.Finding> withSystemModel(List<KnowledgeHealthReport.Finding> findings,
                                                                      List<KnowledgeHealthReport.SystemModelSignal> signals) {
        List<KnowledgeHealthReport.Finding> all = new ArrayList<>(findings);
        all.addAll(KnowledgeHealthFindings.systemModel(signals));
        // Most severe first, the order of every other finding (a stable sort keeps the template order within one).
        all.sort(java.util.Comparator.comparingInt((KnowledgeHealthReport.Finding f) -> -f.severity().ordinal()));
        return List.copyOf(all);
    }

    private Cards cards(Instant now) {
        long verified = 0;
        long draft = 0;
        long stale = 0;
        long disputed = 0;
        long archived = 0;
        for (Object[] row : cards.countsByState()) {
            long count = (Long) row[1];
            switch ((String) row[0]) {
                case "VERIFIED" -> verified = count;
                case "DRAFT" -> draft = count;
                case "STALE" -> stale = count;
                case "DISPUTED" -> disputed = count;
                case "ARCHIVED" -> archived = count;
                default -> { }
            }
        }
        return new Cards(verified, draft, stale, disputed, archived,
                runs.countVerifiedExpiringBy(now, now.plus(properties.verificationDueWithin())));
    }

    private List<Cluster> clusters(RunRow run, boolean showQuestions) {
        List<ClusterRow> rows = runs.clusters(run.id());
        Map<String, String> texts = Map.of();
        if (showQuestions) {
            List<byte[]> hashes = new ArrayList<>();
            rows.forEach(r -> hashes.addAll(r.representativeHashes()));
            texts = runs.questionsByHash(hashes, run.windowFrom(), run.windowTo());
        }
        List<Cluster> clusters = new ArrayList<>();
        for (ClusterRow row : rows) {
            List<String> questions = new ArrayList<>();
            if (showQuestions) {
                for (byte[] hash : row.representativeHashes()) {
                    String text = texts.get(HexFormat.of().formatHex(hash));
                    if (text != null) questions.add(QuestionNormalizer.normalize(text));
                }
            }
            clusters.add(new Cluster(row.id(), row.turns(), row.askers(), row.previousTurns(), row.trend(),
                    row.keywords(), questions));
        }
        return clusters;
    }

    private List<StaleDocument> staleDocuments(Reader admin, DiagnosticSnapshot snapshot, Map<UUID, String> names) {
        if (snapshot.staleDocuments().isEmpty()) return List.of();
        Map<UUID, DocumentAccess> access = catalog.access(admin, snapshot.staleDocuments().stream()
                .map(DiagnosticSnapshot.StaleDocument::documentId).toList());
        return snapshot.staleDocuments().stream().map(d -> {
            DocumentAccess a = access.get(d.documentId());
            return new StaleDocument(d.documentId(), a != null && a.accessible() ? a.fileName() : null,
                    d.collectionId(), names.get(d.collectionId()), d.lastChanged(), d.citations(), d.askers(),
                    d.staleCards());
        }).toList();
    }

    /** The latest candidates, each side re-read with the admin's principals (nothing readable: ids and pages only). */
    private List<Contradiction> contradictions(Reader admin) {
        List<Contradiction> out = new ArrayList<>();
        for (Candidate candidate : contradictions.latest(CONTRADICTIONS)) {
            SourceChunk first = readable(admin, candidate.first());
            SourceChunk second = readable(admin, candidate.second());
            List<Difference> values = first == null || second == null ? List.of()
                    : AnchorCheck.differences(first.content(), second.content(), MAX_DIFFERENCES).stream()
                            .map(d -> new Difference(d.first(), d.second())).toList();
            out.add(new Contradiction(candidate.id(), candidate.similarity(), candidate.differences(),
                    side(candidate.first(), first), side(candidate.second(), second), values,
                    candidate.firstSeenAt(), candidate.lastSeenAt()));
        }
        return out;
    }

    /** The chunk when the admin may read it and its text is still the one the candidate was found with. */
    private SourceChunk readable(Reader admin, ContradictionRepository.Side side) {
        return provenance.sourceChunks(admin, List.of(new SourcePage(side.documentId(), side.page()))).stream()
                .filter(c -> c.chunkId().equals(side.chunkId()) && side.hash().equals(c.contentSha256()))
                .findFirst().orElse(null);
    }

    private static Side side(ContradictionRepository.Side stored, SourceChunk readable) {
        return new Side(stored.documentId(), stored.page(), readable != null,
                readable == null ? null : readable.fileName());
    }
}
