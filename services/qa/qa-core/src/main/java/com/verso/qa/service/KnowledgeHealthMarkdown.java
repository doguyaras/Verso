package com.verso.qa.service;

import com.verso.qa.api.dto.KnowledgeHealthReport;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cluster;
import com.verso.qa.api.dto.KnowledgeHealthReport.CollectionCoverage;
import com.verso.qa.api.dto.KnowledgeHealthReport.Contradiction;
import com.verso.qa.api.dto.KnowledgeHealthReport.FeedbackCell;
import com.verso.qa.api.dto.KnowledgeHealthReport.Finding;
import com.verso.qa.api.dto.KnowledgeHealthReport.Side;
import com.verso.qa.api.dto.KnowledgeHealthReport.StaleDocument;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * The knowledge-health report as Markdown (ADR-0052): the quarterly deliverable the consulting team takes to the
 * customer. Built only from the report the admin may see (the same k-anonymity, question-text policy and access
 * filtering); every name and question is escaped, so a file name cannot inject Markdown, links or HTML. Pure.
 */
public final class KnowledgeHealthMarkdown {

    private static final Locale TR = Locale.forLanguageTag("tr");

    private KnowledgeHealthMarkdown() {
    }

    public static String render(KnowledgeHealthReport report, ZoneId zone) {
        StringBuilder md = new StringBuilder(4096);
        md.append("# Bilgi sağlığı raporu\n\n");
        md.append("- Dönem: ").append(report.from()).append(" – ").append(report.to()).append('\n');
        md.append("- Oluşturulma: ").append(LocalDate.ofInstant(report.generatedAt(), zone)).append('\n');
        if (report.run() != null) {
            md.append("- Teşhis penceresi: ").append(report.run().windowFrom()).append(" – ")
                    .append(report.run().windowTo()).append(" (").append(report.run().questions())
                    .append(" belirsiz soru, ").append(report.run().clusters()).append(" küme)\n");
        }
        md.append("- Gizlilik: her toplamda en az ").append(report.minAskers())
                .append(" farklı kullanıcı; kişi bazında döküm yok; soru metinleri ")
                .append(report.questionTextShown() ? "kurulum politikası gereği gösteriliyor" : "gösterilmiyor")
                .append(".\n\n");

        md.append("## Bulgular ve öneriler\n\n");
        for (Finding finding : report.findings()) {
            md.append("### ").append(severity(finding.severity())).append(' ').append(text(finding.title())).append("\n\n");
            md.append("**Bulgu:** ").append(text(finding.finding())).append("\n\n");
            md.append("**Öneri:** ").append(text(finding.recommendation())).append("\n\n");
        }

        md.append("## Cevapsız ve belirsiz soru kümeleri\n\n");
        if (report.clusters().isEmpty()) {
            md.append("Eşiği geçen küme yok.\n\n");
        } else {
            md.append("| # | Soru | Kullanıcı | Eğilim | Ortak sözcükler | Örnek sorular |\n|---|---|---|---|---|---|\n");
            int n = 1;
            for (Cluster cluster : report.clusters()) {
                md.append("| ").append(n++).append(" | ").append(cluster.turns()).append(" | ").append(cluster.askers())
                        .append(" | ").append(trend(cluster)).append(" | ").append(cell(String.join(", ", cluster.keywords())))
                        .append(" | ").append(cell(String.join(" · ", cluster.representativeQuestions()))).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Eski ama sık kullanılan belgeler\n\n");
        if (report.staleDocuments().isEmpty()) {
            md.append("Yok.\n\n");
        } else {
            md.append("| Belge | Bilgi alanı | Son değişiklik | Atıf | Kullanıcı | Eskiyen kart |\n|---|---|---|---|---|---|\n");
            for (StaleDocument doc : report.staleDocuments()) {
                md.append("| ").append(cell(doc.fileName() == null ? "belge " + doc.documentId() : doc.fileName()))
                        .append(" | ").append(cell(doc.collectionName())).append(" | ")
                        .append(LocalDate.ofInstant(doc.lastChanged(), zone)).append(" | ").append(doc.citations())
                        .append(" | ").append(doc.askers()).append(" | ").append(doc.staleCards()).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Kapsama (bilgi alanı başına)\n\n");
        md.append("Hiç kullanılmayan paylaşılan belge: ").append(report.neverCitedDocuments()).append("\n\n");
        if (!report.coverage().isEmpty()) {
            md.append("| Bilgi alanı | Belge | Dönemde kaynak olan | Kapsama | Hiç kullanılmayan |\n|---|---|---|---|---|\n");
            for (CollectionCoverage cell : report.coverage()) {
                md.append("| ").append(cell(cell.collectionName())).append(" | ").append(cell.documents()).append(" | ")
                        .append(cell.citedInWindow() == null ? "gizli (k)" : cell.citedInWindow()).append(" | ")
                        .append(cell.coverage() == null ? "–" : percent(cell.coverage())).append(" | ")
                        .append(cell.neverCited()).append(" |\n");
            }
            md.append('\n');
        }

        md.append("## Çelişki adayları\n\n");
        if (report.contradictions().isEmpty()) {
            md.append("Yok.\n\n");
        } else {
            md.append("| Belge A | Belge B | Benzerlik | Farklı değerler |\n|---|---|---|---|\n");
            for (Contradiction c : report.contradictions()) {
                String values = c.values().isEmpty() ? "gizli (erişim)" : c.values().stream()
                        .map(v -> v.first() + " / " + v.second()).collect(Collectors.joining(", "));
                md.append("| ").append(side(c.first())).append(" | ").append(side(c.second())).append(" | ")
                        .append(percent(c.similarity())).append(" | ")
                        .append(c.values().isEmpty() ? values : cell(values)).append(" |\n");
            }
            md.append('\n');
        }

        KnowledgeHealthReport.Cards cards = report.cards();
        md.append("## Onaylı bilgi kartları\n\n");
        md.append("| Onaylı | Taslak | Eskimiş | İtirazlı | Arşivde | Onayı yakında dolacak |\n|---|---|---|---|---|---|\n");
        md.append("| ").append(cards.verified()).append(" | ").append(cards.draft()).append(" | ").append(cards.stale())
                .append(" | ").append(cards.disputed()).append(" | ").append(cards.archived()).append(" | ")
                .append(cards.expiringSoon()).append(" |\n\n");

        md.append("## Olumsuz geri bildirim (bilgi alanı ve gerekçe)\n\n");
        if (report.downRatingReasons().isEmpty()) {
            md.append("Eşiği geçen hücre yok.\n\n");
        } else {
            md.append("| Bilgi alanı | Gerekçe | Cevap | Kullanıcı |\n|---|---|---|---|\n");
            for (FeedbackCell cell : report.downRatingReasons()) {
                md.append("| ").append(cell(cell.collectionName())).append(" | ")
                        .append(KnowledgeHealthFindings.reason(cell.reason())).append(" | ").append(cell.ratings())
                        .append(" | ").append(cell.askers()).append(" |\n");
            }
            md.append('\n');
        }

        if (!report.trend().isEmpty()) {
            md.append("## Eğilim\n\n| Pencere sonu | Belirsiz soru | Küme |\n|---|---|---|\n");
            report.trend().forEach(p -> md.append("| ").append(p.windowTo()).append(" | ").append(p.questions())
                    .append(" | ").append(p.clusters()).append(" |\n"));
            md.append('\n');
        }
        md.append("---\nVerso bilgi sağlığı raporu. Bulgular ve öneriler sabit şablonlardan üretilir; bir model yazmaz.\n");
        return md.toString();
    }

    private static String severity(KnowledgeHealthSeverity severity) {
        return switch (severity) {
            case CRITICAL -> "[Kritik]";
            case WARNING -> "[Uyarı]";
            case INFO -> "[Bilgi]";
        };
    }

    private static String trend(Cluster cluster) {
        return switch (cluster.trend()) {
            case NEW -> "yeni";
            case GROWING -> "büyüyor";
            case STABLE -> "durağan";
            case SHRINKING -> "küçülüyor";
        };
    }

    private static String side(Side side) {
        String name = side.readable() && side.fileName() != null ? side.fileName() : "belge " + side.documentId();
        return cell(name + ", s. " + side.page());
    }

    private static String percent(double value) {
        return String.format(TR, "%%%.0f", value * 100);
    }

    /** Text outside a table: one line, Markdown and HTML control characters escaped. */
    static String text(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.replaceAll("[\\r\\n\\t]+", " ").toCharArray()) {
            if ("\\`*_{}[]()<>#+!|~".indexOf(c) >= 0) out.append('\\');
            out.append(c);
        }
        return out.toString();
    }

    /** A table cell: {@link #text} plus a dash for nothing. */
    static String cell(String value) {
        return value == null || value.isBlank() ? "–" : text(value);
    }
}
