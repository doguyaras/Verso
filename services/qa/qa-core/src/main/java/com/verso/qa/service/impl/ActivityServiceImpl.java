package com.verso.qa.service.impl;

import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityDetail;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import com.verso.platform.core.activity.ActivityViewer;
import com.verso.qa.api.dto.ActivityDetailResponse;
import com.verso.qa.api.dto.ActivityItemResponse;
import com.verso.qa.api.dto.ActivityPageResponse;
import com.verso.qa.api.dto.ActivitySummaryResponse;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.ActivityService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Service;

/**
 * Merges the activity sources (ADR-0058). Every source is asked for at most page x size + size entries of the window,
 * so a request never holds more than sources x {@value ActivityService#MAX_DEPTH} entries. A source that throws is
 * left out and the page says {@code partial}; its failure is logged as one line with the source key and the exception
 * class only (never its message: it could carry a name or a path).
 */
@Service
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ActivityServiceImpl implements ActivityService {

    private static final Logger log = LoggerFactory.getLogger(ActivityServiceImpl.class);
    private static final Pattern WORD = Pattern.compile("[A-Z][A-Z0-9_]{1,39}");
    private static final Pattern ID = Pattern.compile("([a-z][a-z0-9-]{1,30})\\.([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9-]{1,30}");
    private static final int SUMMARY_DAYS = 7;
    private static final int MAX_REASONS = 3;
    private static final Comparator<ActivityEntry> NEWEST_FIRST = Comparator.comparing(ActivityEntry::startedAt)
            .reversed().thenComparing(e -> e.id().toString());

    private final Map<String, ActivitySource> sources;
    private final ZoneId zone;
    private final Clock clock;

    public ActivityServiceImpl(List<ActivitySource> sources, UsageMetricsProperties metrics, Clock clock) {
        Map<String, ActivitySource> byKey = new LinkedHashMap<>();
        for (ActivitySource source : sources) {
            if (!KEY.matcher(source.key()).matches() || byKey.putIfAbsent(source.key(), source) != null) {
                throw new IllegalStateException("activity source key must be a unique lowercase word");
            }
        }
        this.sources = Map.copyOf(byKey);
        this.zone = metrics.zone();
        this.clock = clock;
    }

    @Override
    public ActivityPageResponse page(ActivityViewer viewer, Filter filter) {
        ActivityCategory category = parse(ActivityCategory.class, filter.category());
        ActivityStatus status = parse(ActivityStatus.class, filter.status());
        String type = filter.type() == null || filter.type().isBlank() ? null : filter.type();
        if (type != null && !WORD.matcher(type).matches()) throw invalid("type");
        if (filter.size() < 1 || filter.size() > MAX_SIZE || filter.page() < 0
                || (long) (filter.page() + 1) * filter.size() > MAX_DEPTH) {
            throw invalid("page");
        }
        LocalDate today = LocalDate.now(clock.withZone(zone));
        LocalDate to = filter.to() == null ? today : filter.to();
        LocalDate from = filter.from() == null ? to.minusDays(6) : filter.from();
        if (from.isAfter(to) || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) throw invalid("range");

        int depth = (filter.page() + 1) * filter.size();
        // One more than the page needs, so "hasMore" is known without a count query.
        ActivityQuery query = new ActivityQuery(from.atStartOfDay(zone).toInstant(),
                to.plusDays(1).atStartOfDay(zone).toInstant(), filter.collection(),
                Math.min(depth + 1, ActivityQuery.MAX_LIMIT), viewer);
        Collected collected = collect(query);
        List<ActivityEntry> matching = collected.entries().stream()
                .filter(e -> category == null || e.category() == category)
                .filter(e -> type == null || e.type().equals(type))
                .filter(e -> status == null || e.status() == status)
                .sorted(NEWEST_FIRST).toList();
        int start = filter.page() * filter.size();
        List<ActivityItemResponse> items = matching.stream().skip(start).limit(filter.size())
                .map(e -> item(collected.keyOf().get(e), e)).toList();
        boolean hasMore = matching.size() > start + filter.size() && depth < MAX_DEPTH;
        return new ActivityPageResponse(items, filter.page(), filter.size(), hasMore, List.copyOf(sources.keySet()),
                collected.partial());
    }

    @Override
    public ActivitySummaryResponse summary(ActivityViewer viewer) {
        Instant now = clock.instant();
        ActivityQuery query = new ActivityQuery(now.minus(Duration.ofDays(SUMMARY_DAYS)), now.plusSeconds(1), null,
                ActivityQuery.MAX_LIMIT, viewer);
        Collected collected = collect(query);
        List<ActivityEntry> all = collected.entries();
        Instant dayAgo = now.minus(Duration.ofHours(24));
        Map<ActivityCategory, Integer> recent = new EnumMap<>(ActivityCategory.class);
        for (ActivityEntry e : all) {
            if (!e.startedAt().isBefore(dayAgo)) recent.merge(e.category(), 1, Integer::sum);
        }
        int approvals = (int) all.stream().filter(e -> e.category() == ActivityCategory.APPROVAL).count();
        List<ActivityEntry> attention = all.stream().filter(ActivityEntry::attention).toList();
        return new ActivitySummaryResponse(attention.size(), reasons(attention),
                new ActivitySummaryResponse.Counts(recent.getOrDefault(ActivityCategory.RUN, 0),
                        recent.getOrDefault(ActivityCategory.BACKGROUND, 0),
                        recent.getOrDefault(ActivityCategory.HOOK, 0),
                        recent.getOrDefault(ActivityCategory.KNOWLEDGE, 0)),
                approvals, now, collected.partial());
    }

    @Override
    public ActivityDetailResponse detail(ActivityViewer viewer, String id) {
        var match = id == null ? null : ID.matcher(id);
        if (match == null || !match.matches()) throw notFound();
        ActivitySource source = sources.get(match.group(1));
        if (source == null) throw notFound();
        // A failing source is not "not found": its exception reaches the global handler (503 for the database).
        Optional<ActivityDetail> found = source.detail(UUID.fromString(match.group(2)), viewer);
        ActivityDetail detail = found.orElseThrow(ActivityServiceImpl::notFound);
        return new ActivityDetailResponse(item(source.key(), detail.entry()),
                detail.facts().stream().map(f -> new ActivityDetailResponse.Fact(f.label(), f.value())).toList(),
                detail.steps().stream().map(s -> new ActivityDetailResponse.Step(s.label(), s.status().name(),
                        s.detail(), s.durationMs())).toList(),
                detail.sources().stream().map(s -> new ActivityDetailResponse.Source(s.name(), s.format(),
                        s.readable(), s.citations())).toList(),
                detail.modelCalls().stream().map(m -> new ActivityDetailResponse.ModelCall(m.purpose(), m.local(),
                        m.provider(), m.inputTokens(), m.outputTokens(), m.durationMs())).toList());
    }

    /** Every source's entries of the window; a failing source is skipped and marks the result partial. */
    private Collected collect(ActivityQuery query) {
        List<ActivityEntry> entries = new ArrayList<>();
        Map<ActivityEntry, String> keyOf = new java.util.IdentityHashMap<>();
        boolean partial = false;
        for (ActivitySource source : sources.values()) {
            try {
                List<ActivityEntry> found = source.entries(query);
                for (ActivityEntry e : found.subList(0, Math.min(found.size(), query.limit()))) {
                    if (!query.admits(e.collectionId())) continue;
                    boolean inWindow = query.contains(e.startedAt())
                            || (e.category() == ActivityCategory.APPROVAL && e.startedAt().isBefore(query.to()));
                    if (!inWindow) continue;
                    entries.add(e);
                    keyOf.put(e, source.key());
                }
            } catch (RuntimeException e) {
                partial = true;
                log.warn("Activity source unavailable: source={} reason={}", source.key(), e.getClass().getSimpleName());
            }
        }
        return new Collected(entries, keyOf, partial);
    }

    /** At most three Turkish reasons, the most urgent first: failures, then regressions, then approvals. */
    static List<String> reasons(List<ActivityEntry> attention) {
        Map<String, Integer> byType = new LinkedHashMap<>();
        attention.stream().sorted(Comparator.comparingInt(ActivityServiceImpl::urgency))
                .forEach(e -> byType.merge(reasonKey(e), 1, Integer::sum));
        List<String> reasons = new ArrayList<>();
        byType.forEach((key, n) -> reasons.add(reasonText(key, n)));
        return reasons.subList(0, Math.min(MAX_REASONS, reasons.size()));
    }

    private static int urgency(ActivityEntry e) {
        return switch (e.status()) {
            case FAILED -> 0;
            case REGRESSION -> 1;
            case PARTIAL -> 2;
            case PENDING -> 3;
            default -> 4;
        };
    }

    private static String reasonKey(ActivityEntry e) {
        return e.category() == ActivityCategory.APPROVAL ? "APPROVAL" : e.type() + ":" + e.status().name();
    }

    static String reasonText(String key, int n) {
        return switch (key) {
            case "APPROVAL" -> n + " onay bekliyor";
            case "CONNECTOR_SYNC:FAILED" -> n == 1 ? "Bir bağlayıcı senkronu hata verdi" : n + " bağlayıcı senkronu hata verdi";
            case "CONNECTOR_SYNC:PARTIAL" -> n + " bağlayıcı senkronu kısmen tamamlandı";
            case "GOLDEN_EVAL:REGRESSION" -> "Altın değerlendirmede gerileme";
            case "GOLDEN_EVAL:FAILED" -> "Altın değerlendirme tamamlanamadı";
            case "KNOWLEDGE_HEALTH:FAILED" -> "Bilgi sağlığı taraması tamamlanamadı";
            default -> n + " iş dikkat istiyor (" + key.substring(0, key.indexOf(':') < 0 ? key.length()
                    : key.indexOf(':')).toLowerCase(Locale.ROOT).replace('_', ' ') + ")";
        };
    }

    static ActivityItemResponse item(String key, ActivityEntry e) {
        return new ActivityItemResponse(key + "." + e.id(), e.category().name(), e.type(), e.status().name(),
                e.startedAt(), e.finishedAt(), new ActivityItemResponse.Actor(e.actor().kind().name(), e.actor().label()),
                e.title(), e.detail(), e.collectionId(),
                e.link() == null ? null : new ActivityItemResponse.Link(e.link().screen(), e.link().targetId()),
                e.audit() == null ? null : new ActivityItemResponse.AuditRef(e.audit().targetType(),
                        e.audit().targetId(), e.audit().action()),
                e.attention());
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw invalid(type.getSimpleName());
        }
    }

    private static QaServiceException invalid(String what) {
        log.warn("Activity rejected: code={} reason={}", QaErrorCode.ACTIVITY_FILTER_INVALID.getCode(), what);
        return new QaServiceException(QaErrorCode.ACTIVITY_FILTER_INVALID, "ACTIVITY_FILTER_INVALID");
    }

    private static QaServiceException notFound() {
        log.warn("Activity detail rejected: code={} reason=NOT_FOUND", QaErrorCode.ACTIVITY_NOT_FOUND.getCode());
        return new QaServiceException(QaErrorCode.ACTIVITY_NOT_FOUND, "ACTIVITY_NOT_FOUND");
    }

    private record Collected(List<ActivityEntry> entries, Map<ActivityEntry, String> keyOf, boolean partial) {
    }
}
