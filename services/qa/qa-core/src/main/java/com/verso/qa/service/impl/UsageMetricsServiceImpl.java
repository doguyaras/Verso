package com.verso.qa.service.impl;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.DocumentInventory;
import com.verso.document.api.dto.DocumentSummary;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.UsageMetricsResponse;
import com.verso.qa.api.dto.UsageMetricsResponse.CitedDocument;
import com.verso.qa.api.dto.UsageMetricsResponse.Day;
import com.verso.qa.api.dto.UsageMetricsResponse.DocumentEntry;
import com.verso.qa.api.dto.UsageMetricsResponse.Feedback;
import com.verso.qa.api.dto.UsageMetricsResponse.Latency;
import com.verso.qa.api.dto.UsageMetricsResponse.Modes;
import com.verso.qa.api.dto.UsageMetricsResponse.NeverCitedDocuments;
import com.verso.qa.api.dto.UsageMetricsResponse.OutcomeRates;
import com.verso.qa.api.dto.UsageMetricsResponse.Outcomes;
import com.verso.qa.api.dto.UsageMetricsResponse.Totals;
import com.verso.qa.api.dto.UsageMetricsResponse.UnansweredQuestion;
import com.verso.qa.api.dto.UsageMetricsResponse.UnansweredQuestions;
import com.verso.qa.config.QaHistoryProperties;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.repository.TurnRepository;
import com.verso.qa.repository.UsageRepository;
import com.verso.qa.repository.UsageRepository.DayRow;
import com.verso.qa.repository.UsageRepository.DocumentCountRow;
import com.verso.qa.repository.UsageRepository.FeedbackCountRow;
import com.verso.qa.repository.UsageRepository.LatencyRow;
import com.verso.qa.service.LatencyBuckets;
import com.verso.qa.service.QuestionNormalizer;
import com.verso.qa.service.UsageMetricsService;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Admin usage metrics (ADR-0023). Read from the daily rollups, which hold counts only; the one exception is the
 * unanswered-questions list, read from the history under two guards: the installation's policy switch (off by default)
 * and k-anonymity in the query itself (groups smaller than k never leave the database). No account id and no answer
 * text is returned; document names come from the document module, which refuses non-admin callers itself.
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class UsageMetricsServiceImpl implements UsageMetricsService {

    private static final int TOP_DOCUMENTS = 10;
    private static final int NEVER_CITED_LIMIT = 50;

    private final UsageRepository usage;
    private final TurnRepository turns;
    private final DocumentCatalog catalog;
    private final UsageMetricsProperties properties;
    private final QaHistoryProperties history;
    private final Clock clock;

    public UsageMetricsServiceImpl(UsageRepository usage, TurnRepository turns, DocumentCatalog catalog,
                                   UsageMetricsProperties properties, QaHistoryProperties history, Clock clock) {
        this.usage = usage;
        this.turns = turns;
        this.catalog = catalog;
        this.properties = properties;
        this.history = history;
        this.clock = clock;
    }

    @Override
    public UsageMetricsResponse metrics(LocalDate from, LocalDate to) {
        if (to.isBefore(from) || ChronoUnit.DAYS.between(from, to) + 1 > properties.maxRangeDays()) {
            throw new QaServiceException(QaErrorCode.METRICS_RANGE_INVALID, "METRICS_RANGE_INVALID");
        }
        Map<LocalDate, DayRow> days = usage.days(from, to).stream()
                .collect(Collectors.toMap(DayRow::day, Function.identity()));
        Map<LocalDate, Map<Integer, Long>> latencyByDay = new HashMap<>();
        Map<Integer, Long> latencyTotal = new HashMap<>();
        for (LatencyRow row : usage.latency(from, to)) {
            latencyByDay.computeIfAbsent(row.day(), d -> new HashMap<>()).merge(row.bucketMs(), row.turns(), Long::sum);
            latencyTotal.merge(row.bucketMs(), row.turns(), Long::sum);
        }
        Map<LocalDate, long[]> ratingsByDay = new HashMap<>();
        Map<FeedbackReason, Long> downReasons = new EnumMap<>(FeedbackReason.class);
        for (FeedbackReason reason : FeedbackReason.values()) downReasons.put(reason, 0L);
        long up = 0;
        long down = 0;
        long downWithoutReason = 0;
        for (FeedbackCountRow row : usage.feedback(from, to)) {
            long[] counts = ratingsByDay.computeIfAbsent(row.day(), d -> new long[2]);
            if ("UP".equals(row.rating())) {
                counts[0] += row.feedback();
                up += row.feedback();
            } else {
                counts[1] += row.feedback();
                down += row.feedback();
                if (UsageRepository.NO_REASON.equals(row.reason())) downWithoutReason += row.feedback();
                else downReasons.merge(FeedbackReason.valueOf(row.reason()), row.feedback(), Long::sum);
            }
        }

        List<Day> daily = new ArrayList<>();
        long questions = 0;
        long answered = 0;
        long notFound = 0;
        long uncited = 0;
        long local = 0;
        long cloud = 0;
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            DayRow row = days.getOrDefault(day, new DayRow(day, 0, 0, 0, 0, 0, 0, 0));
            Map<Integer, Long> buckets = latencyByDay.getOrDefault(day, Map.of());
            long[] ratings = ratingsByDay.getOrDefault(day, new long[2]);
            daily.add(new Day(day, row.questions(), row.activeUsers(),
                    new Outcomes(row.answered(), row.notFound(), row.uncited()),
                    new Latency(LatencyBuckets.percentile(buckets, 0.50), LatencyBuckets.percentile(buckets, 0.95)),
                    new Modes(row.localMode(), row.cloudMode()), ratings[0], ratings[1]));
            questions += row.questions();
            answered += row.answered();
            notFound += row.notFound();
            uncited += row.uncited();
            local += row.localMode();
            cloud += row.cloudMode();
        }

        // Exact distinct users only where active_account_day still holds the days (retention, ADR-0023).
        LocalDate retainedFrom = LocalDate.ofInstant(clock.instant().minus(Duration.ofDays(history.retentionDays())),
                properties.zone());
        Totals totals = new Totals(questions, usage.activeUsers(from, to), !from.isBefore(retainedFrom),
                new Outcomes(answered, notFound, uncited),
                new OutcomeRates(rate(answered, questions), rate(notFound, questions), rate(uncited, questions)),
                new Latency(LatencyBuckets.percentile(latencyTotal, 0.50), LatencyBuckets.percentile(latencyTotal, 0.95)),
                new Modes(local, cloud), new Feedback(up, down, downReasons, downWithoutReason));

        return new UsageMetricsResponse(from, to, properties.timeZone(), totals, daily, properties.showDocumentNames(),
                topCited(from, to), neverCited(), unanswered(from, to));
    }

    private List<CitedDocument> topCited(LocalDate from, LocalDate to) {
        List<DocumentCountRow> top = usage.topDocuments(from, to, TOP_DOCUMENTS);
        if (top.isEmpty()) return List.of();
        Map<UUID, DocumentSummary> known = catalog.describeAcrossAccounts(top.stream().map(DocumentCountRow::documentId)
                .toList()).stream().collect(Collectors.toMap(DocumentSummary::id, Function.identity()));
        return top.stream().map(row -> {
            DocumentSummary document = known.get(row.documentId());
            return new CitedDocument(row.documentId(), document == null ? null : name(document.fileName()),
                    document == null, row.citations());
        }).toList();
    }

    private NeverCitedDocuments neverCited() {
        List<UUID> cited = usage.citedEver(DocumentCatalog.MAX_IDS + 1);
        boolean complete = cited.size() <= DocumentCatalog.MAX_IDS;
        DocumentInventory inventory = catalog.readyDocumentsExcept(complete ? cited
                : cited.subList(0, DocumentCatalog.MAX_IDS), NEVER_CITED_LIMIT);
        return new NeverCitedDocuments(inventory.total(), complete, inventory.documents().stream()
                .map(d -> new DocumentEntry(d.id(), name(d.fileName()), d.createdAt())).toList());
    }

    /** File names only under the installation's policy (ADR-0023): by default ids and counts only. */
    private String name(String fileName) {
        return properties.showDocumentNames() ? fileName : null;
    }

    private UnansweredQuestions unanswered(LocalDate from, LocalDate to) {
        int k = properties.unansweredMinUsers();
        if (!properties.showUnansweredQuestions()) return new UnansweredQuestions(false, k, List.of());
        List<UnansweredQuestion> questions = turns.unanswered(from, to, k, properties.unansweredLimit()).stream()
                .map(g -> new UnansweredQuestion(QuestionNormalizer.normalize(g.sample()), g.distinctUsers(), g.asked()))
                .toList();
        return new UnansweredQuestions(true, k, questions);
    }

    private static Double rate(long part, long whole) {
        if (whole == 0) return null;
        return Math.round(part * 10_000.0 / whole) / 10_000.0;
    }
}
