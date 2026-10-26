package com.verso.qa.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.HookAction;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthReport;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cards;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cluster;
import com.verso.qa.api.dto.KnowledgeHealthReport.CollectionCoverage;
import com.verso.qa.api.dto.KnowledgeHealthReport.Contradiction;
import com.verso.qa.api.dto.KnowledgeHealthReport.Difference;
import com.verso.qa.api.dto.KnowledgeHealthReport.FeedbackCell;
import com.verso.qa.api.dto.KnowledgeHealthReport.Finding;
import com.verso.qa.api.dto.KnowledgeHealthReport.Side;
import com.verso.qa.api.dto.KnowledgeHealthReport.StaleDocument;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** ADR-0052: the report's Turkish findings come from fixed templates; the Markdown export escapes every name. */
class KnowledgeHealthReportTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");
    private static final KnowledgeHealthReport.Run RUN = new KnowledgeHealthReport.Run(UUID.randomUUID(),
            LocalDate.parse("2026-09-27"), LocalDate.parse("2026-10-26"), NOW, 42, 1, 100);
    private static final Cards CARDS = new Cards(10, 1, 2, 1, 0, 3);

    @Test
    void findings_whenNoRunExists_sayOnlyThat() {
        List<Finding> findings = KnowledgeHealthFindings.of(null, 5, List.of(), List.of(), Duration.ofDays(180),
                List.of(), 0, 0, CARDS, List.of(), 0);
        assertThat(findings).extracting(Finding::code).containsExactly("NO_RUN");
    }

    @Test
    void findings_areTemplatedTurkish_mostSevereFirst() {
        Cluster cluster = new Cluster(UUID.randomUUID(), 12, 6, 8, ClusterTrend.GROWING, List.of("vpn", "bağlantı"),
                List.of());
        StaleDocument stale = new StaleDocument(UUID.randomUUID(), null, UUID.randomUUID(), "İK", NOW, 30, 7, 2);
        FeedbackCell outdated = new FeedbackCell(UUID.randomUUID(), "İK", FeedbackReason.OUTDATED, 9, 5);
        CollectionCoverage low = new CollectionCoverage(UUID.randomUUID(), "Arşiv", 40, 2, 30, 0.05);
        List<Finding> findings = KnowledgeHealthFindings.of(RUN, 5, List.of(cluster), List.of(stale),
                Duration.ofDays(180), List.of(low), 30, 2, CARDS, List.of(outdated), 1);

        assertThat(findings.getFirst().severity()).isEqualTo(KnowledgeHealthSeverity.WARNING);
        assertThat(findings).extracting(Finding::code).contains("UNANSWERED_CLUSTER", "STALE_DOCUMENTS", "CONTRADICTIONS",
                "DISPUTED_CARDS", "STALE_CARDS", "EXPIRING_CARDS", "NEGATIVE_FEEDBACK", "LOW_COVERAGE", "NEVER_CITED",
                "OPEN_FLAGS").doesNotContain("HEALTHY", "NO_RUN");
        Finding clusterFinding = findings.stream().filter(f -> f.code().equals("UNANSWERED_CLUSTER")).findFirst().orElseThrow();
        assertThat(clusterFinding.title()).isEqualTo("Cevapsız kalan soru kümesi: vpn, bağlantı");
        assertThat(clusterFinding.finding()).isEqualTo("6 farklı kullanıcının sorduğu 12 soru bulunamadı, kaynaksız kaldı "
                + "ya da doğrulanamadı; önceki çalışmada 8 soruydu, büyüyor.");
        assertThat(clusterFinding.recommendation()).startsWith("Bu konuyu kapsayan bir belge ekleyin");
        assertThat(findings.stream().filter(f -> f.code().equals("LOW_COVERAGE")).findFirst().orElseThrow().finding())
                .isEqualTo("40 belgenin yalnız %5'i bu dönemde bir cevaba kaynak oldu; 30 belge hiç kullanılmadı.");
        // The same input reads the same: no model, no randomness.
        assertThat(KnowledgeHealthFindings.of(RUN, 5, List.of(cluster), List.of(stale), Duration.ofDays(180),
                List.of(low), 30, 2, CARDS, List.of(outdated), 1)).isEqualTo(findings);
    }

    @Test
    void findings_whenNothingNeedsAttention_sayHealthy() {
        List<Finding> findings = KnowledgeHealthFindings.of(RUN, 5, List.of(), List.of(), Duration.ofDays(180),
                List.of(), 0, 0, new Cards(3, 0, 0, 0, 0, 0), List.of(), 0);
        assertThat(findings).extracting(Finding::code).containsExactly("HEALTHY");
    }

    @Test
    void markdown_escapesNamesAndShowsHiddenValuesAsHidden() {
        UUID hiddenDocument = UUID.randomUUID();
        Contradiction readable = new Contradiction(UUID.randomUUID(), 0.97, 1,
                new Side(UUID.randomUUID(), 3, true, "izin|politika*.pdf"), new Side(UUID.randomUUID(), 4, true, "ik.pdf"),
                List.of(new Difference("14", "16")), NOW, NOW);
        Contradiction hidden = new Contradiction(UUID.randomUUID(), 0.95, 2,
                new Side(hiddenDocument, 1, false, null), new Side(UUID.randomUUID(), 2, true, "ik.pdf"), List.of(), NOW, NOW);
        KnowledgeHealthReport report = new KnowledgeHealthReport(LocalDate.parse("2026-07-01"),
                LocalDate.parse("2026-09-30"), NOW, RUN, 5, false,
                List.of(new Finding("CONTRADICTIONS", KnowledgeHealthSeverity.WARNING, "Çelişen <b>belgeler</b>", "x", "y")),
                List.of(new Cluster(UUID.randomUUID(), 7, 5, null, ClusterTrend.NEW, List.of("vpn"), List.of())),
                List.of(), List.of(), 0, List.of(readable, hidden), CARDS, List.of(), List.of(), 0);
        String md = KnowledgeHealthMarkdown.render(report, ZoneId.of("Europe/Istanbul"));
        assertThat(md).startsWith("# Bilgi sağlığı raporu").contains("Dönem: 2026-07-01 – 2026-09-30")
                .contains("### [Uyarı] Çelişen \\<b\\>belgeler\\</b\\>")
                .contains("izin\\|politika\\*.pdf, s. 3").contains("14 / 16")
                .contains("belge " + hiddenDocument + ", s. 1").contains("gizli (erişim)")
                .contains("soru metinleri gösterilmiyor").doesNotContain("<b>");
    }

    @Test
    void notificationTitles_andRuleDescriptions_areTurkishAndFixed() {
        assertThat(KnowledgeHealthFindings.notificationTitle(HookTrigger.CARD_STALE, HookAction.NOTIFY, "SOURCE_DELETED"))
                .isEqualTo("Onaylı bir bilgi eskidi (kaynak silindi)");
        assertThat(KnowledgeHealthFindings.notificationTitle(HookTrigger.CARD_DISPUTED, HookAction.FLAG, null))
                .isEqualTo("İnceleme: Onaylı bir bilgiye itiraz edildi");
        for (String key : List.of("DOCUMENT_CHANGED_RUN_EVAL", "CLUSTER_EMERGED_NOTIFY", "CLUSTER_GROWING_NOTIFY",
                "CONTRADICTION_FLAG", "CONTRADICTION_DEMOTE_CARD", "CARD_STALE_NOTIFY", "CARD_DISPUTED_FLAG",
                "GOLDEN_RECALL_DROP_NOTIFY", "VERIFICATION_DUE_NOTIFY")) {
            assertThat(KnowledgeHealthFindings.ruleDescription(key)).as(key).doesNotContain("_").endsWith(".");
        }
    }

    @Test
    void systemModelFindings_whenSignalsExist_areOnePerKindWithTitlesNotPeople() {
        UUID doc = UUID.randomUUID();
        List<KnowledgeHealthReport.SystemModelSignal> signals = List.of(
                new KnowledgeHealthReport.SystemModelSignal("UNDEFINED_CODE", "CODE", "WMS-204", "WMS-204",
                        List.of(doc), List.of()),
                new KnowledgeHealthReport.SystemModelSignal("UNDEFINED_CODE", "CODE", "WMS-310", "WMS-310",
                        List.of(doc), List.of()),
                new KnowledgeHealthReport.SystemModelSignal("TERM_CONFLICT", "TERM", "FEFO", "FEFO",
                        List.of(doc, UUID.randomUUID()), List.of("First Expired First Out", "First Ended First Out")),
                new KnowledgeHealthReport.SystemModelSignal("PROCESS_WITHOUT_OWNER", "PROCESS", "iade sureci",
                        "İade Süreci", List.of(doc), List.of()));

        List<Finding> findings = KnowledgeHealthFindings.systemModel(signals);

        assertThat(findings).extracting(Finding::code)
                .containsExactly("TERM_CONFLICTS", "UNDEFINED_CODES", "PROCESSES_WITHOUT_OWNER");
        assertThat(findings.get(1).finding()).contains("2 kod", "WMS-204");
        assertThat(findings.get(2).recommendation()).contains("unvan");
        assertThat(KnowledgeHealthFindings.systemModel(List.of())).isEmpty();
    }
}
