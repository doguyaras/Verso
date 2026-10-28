package com.verso.qa.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.verso.qa.api.dto.ActivityPageResponse;
import com.verso.qa.api.dto.ActivitySummaryResponse;
import com.verso.qa.config.UsageMetricsProperties;
import com.verso.qa.exception.QaErrorCode;
import com.verso.qa.exception.QaServiceException;
import com.verso.qa.service.ActivityService.Filter;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/** The merge of the activity centre (ADR-0058): order, filters, caps, a failing source, the strip's reasons. */
@ExtendWith(OutputCaptureExtension.class)
class ActivityServiceImplTest {

    private static final Instant NOW = Instant.parse("2026-10-28T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final UsageMetricsProperties METRICS = new UsageMetricsProperties("UTC", false, false, 3, 20, 366);
    private static final ActivityViewer VIEWER = new ActivityViewer("acct-secret-sub", List.of("grp-finance"));

    /** A source that returns fixed entries and records the queries it got. */
    static class Fixed implements ActivitySource {
        final String key;
        final List<ActivityEntry> entries;
        final List<ActivityQuery> queries = new ArrayList<>();

        Fixed(String key, List<ActivityEntry> entries) {
            this.key = key;
            this.entries = entries;
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public List<ActivityEntry> entries(ActivityQuery query) {
            queries.add(query);
            return entries;
        }

        @Override
        public Optional<ActivityDetail> detail(UUID id, ActivityViewer viewer) {
            return entries.stream().filter(e -> e.id().equals(id)).findFirst()
                    .map(e -> ActivityDetail.of(e, List.of(new ActivityDetail.Fact("Vaka", "12"))));
        }
    }

    static ActivityEntry entry(ActivityCategory category, String type, ActivityStatus status, Instant at,
                               boolean attention) {
        return new ActivityEntry(UUID.randomUUID(), category, type, status, at, null, Actor.system("Test"), "Başlık",
                null, null, new Link("health", null), new AuditRef("KNOWLEDGE_HEALTH", null, null), attention);
    }

    private static Filter all(int page, int size) {
        return new Filter(null, null, null, null, null, null, page, size);
    }

    @Test
    void page_mergesSourcesNewestFirst_andNamesTheDeployedSources() {
        Fixed a = new Fixed("qa", List.of(entry(ActivityCategory.BACKGROUND, "GOLDEN_EVAL", ActivityStatus.SUCCEEDED,
                NOW.minusSeconds(3600), false)));
        Fixed b = new Fixed("connector", List.of(entry(ActivityCategory.BACKGROUND, "CONNECTOR_SYNC",
                ActivityStatus.FAILED, NOW.minusSeconds(60), true)));
        ActivityPageResponse page = new ActivityServiceImpl(List.of(a, b), METRICS, CLOCK).page(VIEWER, all(0, 25));

        assertThat(page.items()).extracting(i -> i.type()).containsExactly("CONNECTOR_SYNC", "GOLDEN_EVAL");
        assertThat(page.items().getFirst().id()).startsWith("connector.");
        assertThat(page.sources()).containsExactlyInAnyOrder("qa", "connector");
        assertThat(page.partial()).isFalse();
        assertThat(a.queries.getFirst().limit()).isEqualTo(26);
        assertThat(a.queries.getFirst().from()).isEqualTo(Instant.parse("2026-10-22T00:00:00Z"));
        assertThat(a.queries.getFirst().to()).isEqualTo(Instant.parse("2026-10-29T00:00:00Z"));
    }

    @Test
    void page_filtersByCategoryTypeAndStatus_andDropsEntriesOutsideTheWindow() {
        ActivityEntry old = entry(ActivityCategory.KNOWLEDGE, "DOCUMENTS_ADDED", ActivityStatus.SUCCEEDED,
                NOW.minusSeconds(30L * 86400), false);
        ActivityEntry oldApproval = entry(ActivityCategory.APPROVAL, "CARD_TO_VERIFY", ActivityStatus.PENDING,
                NOW.minusSeconds(30L * 86400), true);
        ActivityEntry hook = entry(ActivityCategory.HOOK, "HOOK_ACTION", ActivityStatus.INFO, NOW.minusSeconds(10), false);
        Fixed source = new Fixed("qa", List.of(old, oldApproval, hook));
        ActivityServiceImpl service = new ActivityServiceImpl(List.of(source), METRICS, CLOCK);

        assertThat(service.page(VIEWER, all(0, 25)).items()).extracting(i -> i.type())
                .as("a pending approval stays, whatever its age; an old fact does not")
                .containsExactly("HOOK_ACTION", "CARD_TO_VERIFY");
        assertThat(service.page(VIEWER, new Filter("APPROVAL", null, null, null, null, null, 0, 25)).items())
                .extracting(i -> i.type()).containsExactly("CARD_TO_VERIFY");
        assertThat(service.page(VIEWER, new Filter(null, "HOOK_ACTION", "INFO", null, null, null, 0, 25)).items())
                .hasSize(1);
        assertThat(service.page(VIEWER, new Filter(null, null, "FAILED", null, null, null, 0, 25)).items()).isEmpty();
    }

    @Test
    void page_paginatesWithinTheCaps_andRefusesDeeperPages() {
        List<ActivityEntry> many = IntStream.range(0, 120).mapToObj(i -> entry(ActivityCategory.HOOK, "HOOK_ACTION",
                ActivityStatus.INFO, NOW.minusSeconds(i * 60L), false)).toList();
        ActivityServiceImpl service = new ActivityServiceImpl(List.of(new Fixed("qa", many)), METRICS, CLOCK);

        ActivityPageResponse second = service.page(VIEWER, all(1, 50));
        assertThat(second.items()).hasSize(50);
        assertThat(second.hasMore()).isTrue();
        assertThat(service.page(VIEWER, all(2, 50)).hasMore()).isFalse();
        for (Filter bad : List.of(all(10, 50), all(0, 51), all(0, 0), all(-1, 10),
                new Filter("NOPE", null, null, null, null, null, 0, 10),
                new Filter(null, "lower-case", null, null, null, null, 0, 10),
                new Filter(null, null, "DONE", null, null, null, 0, 10),
                new Filter(null, null, null, LocalDate.of(2026, 10, 20), LocalDate.of(2026, 10, 10), null, 0, 10),
                new Filter(null, null, null, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 10, 10), null, 0, 10))) {
            assertThatThrownBy(() -> service.page(VIEWER, bad)).as(bad.toString())
                    .isInstanceOfSatisfying(QaServiceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QaErrorCode.ACTIVITY_FILTER_INVALID));
        }
    }

    @Test
    void page_whenASourceFails_returnsTheOthersAsPartial_andLogsNoMessage(CapturedOutput output) {
        ActivitySource broken = new Fixed("document", List.of()) {
            @Override
            public List<ActivityEntry> entries(ActivityQuery query) {
                throw new IllegalStateException("contract.pdf of acct-secret-sub");
            }
        };
        Fixed fine = new Fixed("qa", List.of(entry(ActivityCategory.HOOK, "HOOK_ACTION", ActivityStatus.INFO, NOW, false)));
        ActivityPageResponse page = new ActivityServiceImpl(List.of(broken, fine), METRICS, CLOCK).page(VIEWER, all(0, 10));

        assertThat(page.partial()).isTrue();
        assertThat(page.items()).hasSize(1);
        assertThat(output.getOut()).contains("source=document reason=IllegalStateException")
                .doesNotContain("contract.pdf").doesNotContain("acct-secret-sub").doesNotContain("grp-finance");
    }

    @Test
    void items_neverCarryTheViewer() {
        Fixed source = new Fixed("qa", List.of(entry(ActivityCategory.HOOK, "HOOK_ACTION", ActivityStatus.INFO, NOW, false)));
        String json = new ActivityServiceImpl(List.of(source), METRICS, CLOCK).page(VIEWER, all(0, 10)).toString();
        assertThat(json).doesNotContain("acct-secret-sub").doesNotContain("grp-finance");
    }

    @Test
    void summary_countsAttentionAndTheLastDay_withTheMostUrgentReasonsFirst() {
        Fixed source = new Fixed("qa", List.of(
                entry(ActivityCategory.APPROVAL, "CARD_TO_VERIFY", ActivityStatus.PENDING, NOW.minusSeconds(3 * 86400L), true),
                entry(ActivityCategory.APPROVAL, "PROCEDURE_APPROVAL", ActivityStatus.PENDING, NOW.minusSeconds(60), true),
                entry(ActivityCategory.BACKGROUND, "GOLDEN_EVAL", ActivityStatus.REGRESSION, NOW.minusSeconds(120), true),
                entry(ActivityCategory.BACKGROUND, "CONNECTOR_SYNC", ActivityStatus.FAILED, NOW.minusSeconds(30), true),
                entry(ActivityCategory.HOOK, "HOOK_ACTION", ActivityStatus.INFO, NOW.minusSeconds(30), false),
                entry(ActivityCategory.KNOWLEDGE, "DOCUMENTS_ADDED", ActivityStatus.SUCCEEDED, NOW.minusSeconds(2 * 86400L), false)));
        ActivitySummaryResponse summary = new ActivityServiceImpl(List.of(source), METRICS, CLOCK).summary(VIEWER);

        assertThat(summary.attention()).isEqualTo(4);
        assertThat(summary.pendingApprovals()).isEqualTo(2);
        assertThat(summary.reasons()).containsExactly("Bir bağlayıcı senkronu hata verdi",
                "Altın değerlendirmede gerileme", "2 onay bekliyor");
        assertThat(summary.last24h()).isEqualTo(new ActivitySummaryResponse.Counts(0, 2, 1, 0));
        assertThat(summary.checkedAt()).isEqualTo(NOW);
    }

    @Test
    void summary_whenNothingNeedsAPerson_hasNoReason() {
        ActivitySummaryResponse summary = new ActivityServiceImpl(List.of(new Fixed("qa", List.of())), METRICS, CLOCK)
                .summary(VIEWER);
        assertThat(summary.attention()).isZero();
        assertThat(summary.reasons()).isEmpty();
    }

    @Test
    void detail_findsTheEntryBySourceAndId_andRefusesEverythingElse() {
        ActivityEntry run = entry(ActivityCategory.BACKGROUND, "GOLDEN_EVAL", ActivityStatus.SUCCEEDED, NOW, false);
        ActivityServiceImpl service = new ActivityServiceImpl(List.of(new Fixed("qa", List.of(run))), METRICS, CLOCK);

        var detail = service.detail(VIEWER, "qa." + run.id());
        assertThat(detail.item().id()).isEqualTo("qa." + run.id());
        assertThat(detail.facts()).extracting(f -> f.label()).containsExactly("Vaka");
        for (String bad : new String[] {"qa." + UUID.randomUUID(), "skills." + run.id(), "qa", "qa.not-a-uuid",
                "../qa." + run.id(), null, "QA." + run.id()}) {
            assertThatThrownBy(() -> service.detail(VIEWER, bad)).as(String.valueOf(bad))
                    .isInstanceOfSatisfying(QaServiceException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(QaErrorCode.ACTIVITY_NOT_FOUND));
        }
    }

    /** Regression (spring review): a source that fails on the drawer is a failure (503 upstream), not "not found". */
    @Test
    void detail_whenTheSourceFails_propagatesInsteadOfNotFound() {
        ActivitySource broken = new Fixed("qa", List.of()) {
            @Override
            public Optional<ActivityDetail> detail(UUID id, ActivityViewer viewer) {
                throw new org.springframework.dao.DataAccessResourceFailureException("db down");
            }
        };
        ActivityServiceImpl service = new ActivityServiceImpl(List.of(broken), METRICS, CLOCK);
        assertThatThrownBy(() -> service.detail(VIEWER, "qa." + UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }

    @Test
    void construction_refusesDuplicateOrMalformedKeys() {
        assertThatThrownBy(() -> new ActivityServiceImpl(List.of(new Fixed("qa", List.of()), new Fixed("qa", List.of())),
                METRICS, CLOCK)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new ActivityServiceImpl(List.of(new Fixed("Q A", List.of())), METRICS, CLOCK))
                .isInstanceOf(IllegalStateException.class);
    }
}
