package com.verso.qa.activity;

import com.verso.document.api.DocumentCatalog;
import com.verso.document.api.dto.DocumentAccess;
import com.verso.document.api.dto.Reader;
import com.verso.platform.core.activity.ActivityCategory;
import com.verso.platform.core.activity.ActivityDetail;
import com.verso.platform.core.activity.ActivityEntry;
import com.verso.platform.core.activity.ActivityEntry.Actor;
import com.verso.platform.core.activity.ActivityEntry.AuditRef;
import com.verso.platform.core.activity.ActivityEntry.Link;
import com.verso.platform.core.activity.ActivityQuery;
import com.verso.platform.core.activity.ActivitySource;
import com.verso.platform.core.activity.ActivityStatus;
import com.verso.platform.core.activity.ActivityViewer;
import com.verso.qa.repository.KnowledgeCardRepository;
import com.verso.qa.repository.QaActivityRepository;
import com.verso.qa.repository.QaActivityRepository.ContradictionRow;
import com.verso.qa.repository.QaActivityRepository.EvalRun;
import com.verso.qa.repository.QaActivityRepository.HealthRun;
import com.verso.qa.repository.QaActivityRepository.HookRow;
import com.verso.qa.repository.QaActivityRepository.StateRow;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The qa module's part of the activity centre (ADR-0058): the nightly golden-set check (ADR-0049), the knowledge-health
 * scans and hook actions (ADR-0052), cards to verify or gone stale, procedures waiting for approval (ADR-0053) and the
 * contradictions still seen by the scan. A card's question is shown only when the admin may read every cited document,
 * like the card list does (ADR-0028); a contradiction names its documents under the same rule. No account id is read.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
class QaActivitySource implements ActivitySource {

    private static final Logger log = LoggerFactory.getLogger(QaActivitySource.class);
    /** A contradiction the scan has not seen again for this long is history, not a pending decision. */
    private static final Duration CONTRADICTION_FRESH = Duration.ofDays(30);
    private static final Actor EVAL = Actor.system("Gece altın değerlendirmesi");
    private static final Actor HEALTH = Actor.system("Bilgi sağlığı taraması");
    private static final Actor HOOKS = Actor.system("Kanca kuralı");
    private static final Actor ADMINS = Actor.role("Bilgi sahibi (yönetici rolü)");

    private final QaActivityRepository activity;
    private final KnowledgeCardRepository cards;
    private final DocumentCatalog catalog;

    QaActivitySource(QaActivityRepository activity, KnowledgeCardRepository cards, DocumentCatalog catalog) {
        this.activity = activity;
        this.cards = cards;
        this.catalog = catalog;
    }

    @Override
    public String key() {
        return "qa";
    }

    @Override
    public List<ActivityEntry> entries(ActivityQuery q) {
        List<ActivityEntry> all = new ArrayList<>();
        int limit = q.limit();
        if (q.collection() == null) {
            activity.evalRuns(q.from(), q.to(), limit).forEach(r -> all.add(eval(r)));
            activity.healthRuns(q.from(), q.to(), limit).forEach(r -> all.add(health(r)));
            activity.hooks(q.from(), q.to(), limit).forEach(r -> all.add(hook(r)));
        }
        List<StateRow> toVerify = activity.cards("DRAFT", q.to(), limit);
        List<StateRow> disputed = activity.cards("DISPUTED", q.to(), limit);
        List<StateRow> stale = activity.cards("STALE", q.to(), limit).stream()
                .filter(r -> q.contains(r.updatedAt())).toList();
        Map<UUID, String> questions = readableQuestions(q.viewer(),
                concat(toVerify, disputed, stale).stream().map(StateRow::id).toList());
        if (q.collection() == null) {
            toVerify.forEach(r -> all.add(card(r, "CARD_TO_VERIFY", ActivityCategory.APPROVAL, questions)));
            disputed.forEach(r -> all.add(card(r, "CARD_DISPUTED", ActivityCategory.APPROVAL, questions)));
            stale.forEach(r -> all.add(card(r, "CARD_STALE", ActivityCategory.KNOWLEDGE, questions)));
            activity.skills("DRAFT", q.to(), limit).forEach(r -> all.add(procedure(r, false)));
            activity.skills("STALE", q.to(), limit).stream().filter(r -> q.contains(r.updatedAt()))
                    .forEach(r -> all.add(procedure(r, true)));
            contradictions(q, limit).forEach(all::add);
        }
        all.sort(Comparator.comparing(ActivityEntry::startedAt).reversed());
        return all.subList(0, Math.min(all.size(), limit));
    }

    @Override
    public Optional<ActivityDetail> detail(UUID id, ActivityViewer viewer) {
        Optional<ActivityDetail> run = activity.evalRun(id).map(r -> ActivityDetail.of(eval(r), List.of(
                fact("Tetikleyen", "SCHEDULED".equals(r.trigger()) ? "Zamanlanmış" : "Elle"),
                fact("Vaka", r.cases()), fact("Bulunan", r.found()), fact("Kaçan", r.missed()),
                fact("Kaynağı kaybolan", r.sourceLost()),
                fact("Geri çağırma", r.recall() == null ? "—" : percent(r.recall())),
                fact("Önceki gece", r.baseline() == null ? "—" : percent(r.baseline())))));
        if (run.isPresent()) return run;
        run = activity.healthRun(id).map(r -> ActivityDetail.of(health(r), List.of(
                fact("Tetikleyen", "SCHEDULED".equals(r.trigger()) ? "Zamanlanmış" : "Elle"),
                fact("İncelenen soru", r.questions()), fact("Küme", r.clusters()),
                fact("Çelişki denetimi", r.contradictionsChecked()))));
        if (run.isPresent()) return run;
        return activity.hook(id).map(r -> ActivityDetail.of(hook(r), List.of(
                fact("Kural", r.ruleKey()), fact("Tetik", r.trigger()), fact("Eylem", r.action()),
                fact("Önem", r.severity()), fact("Okundu", r.readAt() == null ? "Hayır" : "Evet"))));
    }

    private ActivityEntry eval(EvalRun r) {
        ActivityStatus status = switch (r.status()) {
            case "RUNNING" -> ActivityStatus.RUNNING;
            case "FAILED" -> ActivityStatus.FAILED;
            default -> r.alert() ? ActivityStatus.REGRESSION : ActivityStatus.SUCCEEDED;
        };
        String detail = r.cases() + " vakadan " + r.found() + " bulundu"
                + (r.recall() == null ? "" : " (" + percent(r.recall()) + ")")
                + (r.alert() ? " · gerileme" : "");
        return new ActivityEntry(r.id(), ActivityCategory.BACKGROUND, "GOLDEN_EVAL", status, r.startedAt(),
                r.finishedAt(), EVAL, "Gece altın değerlendirmesi", detail, null, new Link("knowledge", null),
                new AuditRef("KNOWLEDGE_EVAL", r.id(), "KNOWLEDGE_EVAL_RUN"),
                status == ActivityStatus.FAILED || status == ActivityStatus.REGRESSION);
    }

    private ActivityEntry health(HealthRun r) {
        ActivityStatus status = switch (r.status()) {
            case "RUNNING" -> ActivityStatus.RUNNING;
            case "FAILED" -> ActivityStatus.FAILED;
            default -> ActivityStatus.SUCCEEDED;
        };
        return new ActivityEntry(r.id(), ActivityCategory.BACKGROUND, "KNOWLEDGE_HEALTH", status, r.startedAt(),
                r.finishedAt(), HEALTH, "Bilgi sağlığı taraması", r.questions() + " soru · " + r.clusters()
                + " küme · " + r.contradictionsChecked() + " çelişki denetimi", null, new Link("health", null),
                new AuditRef("KNOWLEDGE_HEALTH", r.id(), "KNOWLEDGE_DIAGNOSTICS_RUN"), status == ActivityStatus.FAILED);
    }

    private static ActivityEntry hook(HookRow r) {
        String subject = switch (r.subjectType()) {
            case "DOCUMENT" -> "belge";
            case "KNOWLEDGE_CARD" -> "onaylı kart";
            case "QUESTION_CLUSTER" -> "soru kümesi";
            case "CONTRADICTION" -> "çelişki";
            case "EVAL_RUN" -> "altın değerlendirme";
            default -> "kayıt";
        };
        String action = "FLAG".equals(r.action()) ? "işaretlendi" : "bildirildi";
        return new ActivityEntry(r.id(), ActivityCategory.HOOK, "HOOK_ACTION",
                r.readAt() == null ? ActivityStatus.INFO : ActivityStatus.SUCCEEDED, r.createdAt(), r.createdAt(),
                HOOKS, "Kanca: " + subject + " " + action, "Kural: " + r.ruleKey()
                + (r.detail() == null ? "" : " · " + r.detail()), null, new Link("health", null),
                new AuditRef("KNOWLEDGE_HOOK", r.id(), "KNOWLEDGE_HOOK_FIRED"), false);
    }

    private static ActivityEntry card(StateRow r, String type, ActivityCategory category, Map<UUID, String> questions) {
        String question = questions.get(r.id());
        String title = switch (type) {
            case "CARD_TO_VERIFY" -> question == null ? "Onaylı bilgi önerisi doğrulama bekliyor" : "Doğrulanacak: " + question;
            case "CARD_DISPUTED" -> question == null ? "Onaylı bilgi itiraz aldı" : "İtiraz: " + question;
            default -> question == null ? "Onaylı bilgi eskidi" : "Eskidi: " + question;
        };
        String detail = r.reason() == null ? null : "Neden: " + r.reason();
        return new ActivityEntry(r.id(), category, type,
                category == ActivityCategory.APPROVAL ? ActivityStatus.PENDING : ActivityStatus.INFO, r.updatedAt(),
                null, ADMINS,
                title, detail, null, new Link("knowledge", r.id()),
                new AuditRef("KNOWLEDGE_CARD", r.id(), "CARD_STALE".equals(type) ? "KNOWLEDGE_CARD_STALE"
                        : "CARD_DISPUTED".equals(type) ? "KNOWLEDGE_CARD_DISPUTED" : "KNOWLEDGE_CARD_CREATED"),
                category == ActivityCategory.APPROVAL);
    }

    private static ActivityEntry procedure(StateRow r, boolean stale) {
        return new ActivityEntry(r.id(), stale ? ActivityCategory.KNOWLEDGE : ActivityCategory.APPROVAL,
                stale ? "PROCEDURE_STALE" : "PROCEDURE_APPROVAL", stale ? ActivityStatus.INFO : ActivityStatus.PENDING,
                r.updatedAt(), null, ADMINS, (stale ? "Prosedür eskidi: " : "Prosedür onay bekliyor: ") + r.name(),
                "Sürüm " + r.version() + (r.reason() == null ? "" : " · " + r.reason()), null,
                new Link("skills", r.id()), new AuditRef("SKILL", r.id(), stale ? "SKILL_STALE" : "SKILL_DRAFTED"),
                !stale);
    }

    private List<ActivityEntry> contradictions(ActivityQuery q, int limit) {
        Instant since = q.to().minus(CONTRADICTION_FRESH);
        List<ContradictionRow> rows = activity.contradictions(since, q.to(), limit);
        Set<UUID> documents = new HashSet<>();
        rows.forEach(r -> {
            documents.add(r.documentA());
            documents.add(r.documentB());
        });
        Map<UUID, DocumentAccess> access = documents.isEmpty() ? Map.of() : catalog.access(reader(q.viewer()), documents);
        return rows.stream().map(r -> {
            String a = nameOf(access.get(r.documentA()));
            String b = nameOf(access.get(r.documentB()));
            String title = a != null && b != null ? "Çelişki: " + a + " · " + b : "Belgeler arasında çelişki";
            return new ActivityEntry(r.id(), ActivityCategory.APPROVAL, "CONTRADICTION", ActivityStatus.PENDING,
                    r.firstSeenAt(), null, HEALTH, title, r.differences() + " farklı değer · s. " + r.pageA() + " / s. "
                    + r.pageB(), null, new Link("health", null), new AuditRef("CONTRADICTION", r.id(), null), true);
        }).toList();
    }

    /** The questions of the cards whose every cited document the viewer may read (ADR-0028). */
    private Map<UUID, String> readableQuestions(ActivityViewer viewer, List<UUID> cardIds) {
        if (cardIds.isEmpty()) return Map.of();
        try {
            Map<UUID, Set<UUID>> cited = cards.citedDocuments(cardIds);
            Set<UUID> documents = new HashSet<>();
            cited.values().forEach(documents::addAll);
            Map<UUID, DocumentAccess> access = documents.isEmpty() ? Map.of()
                    : catalog.access(reader(viewer), documents);
            List<UUID> readable = cardIds.stream().filter(id -> {
                Set<UUID> sources = cited.getOrDefault(id, Set.of());
                return !sources.isEmpty() && sources.stream()
                        .allMatch(d -> access.containsKey(d) && access.get(d).accessible());
            }).toList();
            return readable.isEmpty() ? Map.of() : activity.questions(readable);
        } catch (RuntimeException e) {
            log.warn("Activity card names unavailable: reason={}", e.getClass().getSimpleName());
            return Map.of();
        }
    }

    private static Reader reader(ActivityViewer viewer) {
        return new Reader(viewer.accountId(), viewer.groups());
    }

    private static String nameOf(DocumentAccess access) {
        return access != null && access.accessible() ? access.fileName() : null;
    }

    @SafeVarargs
    private static <T> List<T> concat(Collection<T>... parts) {
        List<T> all = new ArrayList<>();
        for (Collection<T> part : parts) all.addAll(part);
        return all;
    }

    private static ActivityDetail.Fact fact(String label, Object value) {
        return new ActivityDetail.Fact(label, String.valueOf(value));
    }

    private static String percent(double ratio) {
        return "%" + String.format(java.util.Locale.forLanguageTag("tr"), "%.1f", ratio * 100);
    }
}
