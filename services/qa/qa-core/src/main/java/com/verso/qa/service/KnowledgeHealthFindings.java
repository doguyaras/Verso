package com.verso.qa.service;

import com.verso.qa.api.dto.ClusterTrend;
import com.verso.qa.api.dto.FeedbackReason;
import com.verso.qa.api.dto.HookAction;
import com.verso.qa.api.dto.HookTrigger;
import com.verso.qa.api.dto.KnowledgeHealthReport;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cards;
import com.verso.qa.api.dto.KnowledgeHealthReport.Cluster;
import com.verso.qa.api.dto.KnowledgeHealthReport.CollectionCoverage;
import com.verso.qa.api.dto.KnowledgeHealthReport.FeedbackCell;
import com.verso.qa.api.dto.KnowledgeHealthReport.Finding;
import com.verso.qa.api.dto.KnowledgeHealthReport.StaleDocument;
import com.verso.qa.api.dto.KnowledgeHealthSeverity;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The Turkish "finding + recommendation" texts of the knowledge-health report and the hook texts (ADR-0052), from
 * fixed templates: no model writes them, so the same data always reads the same and no document or question text can
 * steer them. Pure; unit-tested.
 */
public final class KnowledgeHealthFindings {

    /** Clusters that get a finding of their own (the rest are in the cluster table). */
    static final int CLUSTER_FINDINGS = 5;
    /** A collection with at least this many documents and less than this coverage gets a finding. */
    static final int COVERAGE_MIN_DOCUMENTS = 5;
    static final double COVERAGE_LOW = 0.20;

    private static final Locale TR = Locale.forLanguageTag("tr");

    private KnowledgeHealthFindings() {
    }

    /** What the report's data says, most severe first (then in template order). */
    public static List<Finding> of(KnowledgeHealthReport.Run run, int minAskers, List<Cluster> clusters,
                                   List<StaleDocument> stale, Duration staleAfter, List<CollectionCoverage> coverage,
                                   long neverCited, int contradictions, Cards cards, List<FeedbackCell> feedback,
                                   long openFlags) {
        List<Finding> findings = new ArrayList<>();
        if (run == null) {
            findings.add(new Finding("NO_RUN", KnowledgeHealthSeverity.INFO, "Henüz teşhis yok",
                    "Seçilen aralıkta tamamlanmış bir teşhis çalışması yok.",
                    "Teşhisi şimdi çalıştırın ya da gece çalışmasını bekleyin; rapor en son çalışmanın verisiyle oluşur."));
            return findings;
        }
        for (Cluster cluster : clusters.subList(0, Math.min(CLUSTER_FINDINGS, clusters.size()))) {
            boolean urgent = cluster.trend() == ClusterTrend.GROWING || cluster.askers() >= 2 * minAskers;
            findings.add(new Finding("UNANSWERED_CLUSTER", urgent ? KnowledgeHealthSeverity.WARNING
                    : KnowledgeHealthSeverity.INFO, "Cevapsız kalan soru kümesi: " + keywords(cluster.keywords()),
                    String.format(TR, "%d farklı kullanıcının sorduğu %d soru bulunamadı, kaynaksız kaldı ya da "
                            + "doğrulanamadı%s.", cluster.askers(), cluster.turns(), trend(cluster)),
                    "Bu konuyu kapsayan bir belge ekleyin ya da mevcut belgeyi güncelleyin; konunun sahibini belirleyip "
                            + "kümeyi ona verin. Doğru cevap oluşunca onu onaylı bilgi kartına çevirin."));
        }
        if (!stale.isEmpty()) {
            int staleCards = stale.stream().mapToInt(StaleDocument::staleCards).sum();
            findings.add(new Finding("STALE_DOCUMENTS", staleCards > 0 ? KnowledgeHealthSeverity.WARNING
                    : KnowledgeHealthSeverity.INFO, "Eski ama sık kullanılan belgeler",
                    String.format(TR, "%d belge %d gündür değişmedi ama bu dönemde en az %d farklı kullanıcıya kaynak "
                            + "oldu; bu belgelere dayanan %d onaylı bilgi kartı eskidi.", stale.size(),
                            staleAfter.toDays(), minAskers, staleCards),
                    "Belge sahiplerinden güncelliği teyit etmelerini isteyin; değiştiyse güncel sürümü yükleyin. "
                            + "Eskiyen kartları güncel cevaptan yeniden oluşturun."));
        }
        if (contradictions > 0) {
            findings.add(new Finding("CONTRADICTIONS", KnowledgeHealthSeverity.WARNING, "Çelişen belgeler",
                    String.format(TR, "%d belge çifti aynı konuda farklı sayı ya da kod veriyor.", contradictions),
                    "Her çifti konunun sahibiyle inceleyin; yanlış olanı düzeltin ya da eski sürümü kaldırın. Çelişen "
                            + "parçaya dayanan onaylı bilgi kartlarını yeniden gözden geçirin."));
        }
        if (cards.disputed() > 0) {
            findings.add(new Finding("DISPUTED_CARDS", KnowledgeHealthSeverity.WARNING, "İtirazlı onaylı bilgiler",
                    String.format(TR, "%d onaylı bilgi kartı birden fazla kullanıcı yanlış bulduğu için kullanımdan "
                            + "çıktı.", cards.disputed()),
                    "Kartın kaynaklarını yeniden okuyun: cevap doğruysa yeniden onaylayın, değilse arşivleyip güncel "
                            + "cevaptan yeni kart oluşturun."));
        }
        if (cards.stale() > 0) {
            findings.add(new Finding("STALE_CARDS", KnowledgeHealthSeverity.INFO, "Eskiyen onaylı bilgiler",
                    String.format(TR, "%d onaylı bilgi kartı kaynağı değiştiği ya da onay süresi dolduğu için "
                            + "kullanılmıyor.", cards.stale()),
                    "Sık sorulan konulardaki kartları güncel belgelerden yeniden oluşturun; süresi dolanları yeniden "
                            + "onaylayın."));
        }
        if (cards.expiringSoon() > 0) {
            findings.add(new Finding("EXPIRING_CARDS", KnowledgeHealthSeverity.INFO, "Onayı yakında dolacak bilgiler",
                    String.format(TR, "%d onaylı bilgi kartının onay süresi yakında doluyor.", cards.expiringSoon()),
                    "Kartları süresi dolmadan gözden geçirip yeniden onaylayın; aksi halde kullanımdan çıkarlar."));
        }
        for (FeedbackCell cell : feedback) {
            boolean serious = cell.reason() == FeedbackReason.OUTDATED || cell.reason() == FeedbackReason.INCORRECT_OR_MISSING;
            findings.add(new Finding("NEGATIVE_FEEDBACK", serious ? KnowledgeHealthSeverity.WARNING
                    : KnowledgeHealthSeverity.INFO, "Olumsuz geri bildirim: " + name(cell.collectionName()),
                    String.format(TR, "%d farklı kullanıcı, bu bilgi alanının belgelerine dayanan %d cevabı \"%s\" "
                            + "gerekçesiyle olumsuz değerlendirdi.", cell.askers(), cell.ratings(), reason(cell.reason())),
                    recommendation(cell.reason())));
        }
        for (CollectionCoverage cell : coverage) {
            if (cell.coverage() == null || cell.documents() < COVERAGE_MIN_DOCUMENTS || cell.coverage() >= COVERAGE_LOW) {
                continue;
            }
            findings.add(new Finding("LOW_COVERAGE", KnowledgeHealthSeverity.INFO, "Az kullanılan bilgi alanı: "
                    + name(cell.collectionName()),
                    String.format(TR, "%d belgenin yalnız %%%d'i bu dönemde bir cevaba kaynak oldu; %d belge hiç "
                            + "kullanılmadı.", cell.documents(), Math.round(cell.coverage() * 100), cell.neverCited()),
                    "Kullanılmayan belgeleri gözden geçirin: güncel değilse kaldırın, değerliyse çalışanlara duyurun ve "
                            + "başlıklarını sorulan dile yaklaştırın."));
        }
        if (neverCited > 0) {
            findings.add(new Finding("NEVER_CITED", KnowledgeHealthSeverity.INFO, "Hiç kullanılmayan belgeler",
                    String.format(TR, "%d paylaşılan belge şimdiye kadar hiçbir cevaba kaynak olmadı.", neverCited),
                    "Bu belgelerin hâlâ gerekli olup olmadığını sahipleriyle konuşun; gereksiz ve eski belgeler aramayı "
                            + "bulandırır."));
        }
        if (openFlags > 0) {
            findings.add(new Finding("OPEN_FLAGS", KnowledgeHealthSeverity.INFO, "İnceleme bekleyen işaretler",
                    String.format(TR, "%d işaret (itiraz, çelişki) bir yöneticinin incelemesini bekliyor.", openFlags),
                    "Bildirimler listesinden her işareti inceleyip okundu olarak işaretleyin."));
        }
        boolean attention = findings.stream().anyMatch(f -> f.severity() != KnowledgeHealthSeverity.INFO);
        if (!attention) {
            findings.add(new Finding("HEALTHY", KnowledgeHealthSeverity.INFO, "Belirgin bir sorun yok",
                    "Bu dönemde dikkat isteyen bir küme, çelişki ya da itiraz bulunmadı.",
                    "Raporu üç ayda bir danışmanlık ekibiyle gözden geçirmeye devam edin."));
        }
        List<Finding> sorted = new ArrayList<>(findings);
        sorted.sort(Comparator.comparingInt((Finding f) -> -f.severity().ordinal()));
        return List.copyOf(sorted);
    }

    /** The Turkish words of a hook rule (the admin screen); unknown keys read as their key. */
    public static String ruleDescription(String key) {
        return switch (key) {
            case "DOCUMENT_CHANGED_RUN_EVAL" -> "Onaylı bir bilginin kaynağı silinince, değişince ya da taşınınca kontrol "
                    + "setini (model çağırmadan) yeniden çalıştırır.";
            case "CLUSTER_EMERGED_NOTIFY" -> "Yeni bir cevapsız soru kümesi oluşunca yöneticilere bildirir.";
            case "CLUSTER_GROWING_NOTIFY" -> "Cevapsız soru kümesi belirgin büyüyünce yöneticilere bildirir.";
            case "CONTRADICTION_FLAG" -> "Çelişen iki belge bulununca incelemeye işaretler.";
            case "CONTRADICTION_DEMOTE_CARD" -> "Çelişen bir parçaya dayanan onaylı bilgi kartını kullanımdan çıkarır "
                    + "(eskimiş yapar). Varsayılan kapalı.";
            case "CARD_STALE_NOTIFY" -> "Onaylı bir bilgi kartı eskiyince yöneticilere bildirir.";
            case "CARD_DISPUTED_FLAG" -> "Onaylı bir bilgi kartı itiraz eşiğini geçince incelemeye işaretler.";
            case "GOLDEN_RECALL_DROP_NOTIFY" -> "Gece kontrolünde isabet düşünce ya da kaynaklar kayınca bildirir.";
            case "VERIFICATION_DUE_NOTIFY" -> "Onay süresi yakında dolacak kartları bildirir.";
            case "SKILL_STALE_NOTIFY" -> "Onaylı bir prosedürün kaynağı silinince, değişince ya da taşınınca sahiplerine "
                    + "(yöneticilere) bildirir.";
            default -> key;
        };
    }

    /** The title of a hook notification, from its trigger and action (and a fixed detail word). */
    public static String notificationTitle(HookTrigger trigger, HookAction action, String detail) {
        String title = switch (trigger) {
            case DOCUMENT_CHANGED -> "Onaylı bilgiye kaynak olan bir belge değişti";
            case CLUSTER_EMERGED -> "Yeni bir cevapsız soru kümesi";
            case CLUSTER_GROWING -> "Cevapsız soru kümesi büyüyor";
            case CONTRADICTION_FOUND -> "Çelişen iki belge bulundu";
            case CARD_STALE -> "Onaylı bir bilgi eskidi";
            case CARD_DISPUTED -> "Onaylı bir bilgiye itiraz edildi";
            case GOLDEN_RECALL_DROP -> "Gece kontrolünde düşüş";
            case VERIFICATION_DUE -> "Onay süresi doluyor";
            case SKILL_STALE -> "Bir prosedür eskidi";
        };
        String reason = detail == null ? null : switch (detail) {
            case "SOURCE_DELETED" -> "kaynak silindi";
            case "SOURCE_REPLACED" -> "kaynağın yeni sürümü geldi";
            case "SOURCE_MOVED" -> "kaynak taşındı";
            case "SOURCE_CHANGED" -> "kaynak metni değişti";
            case "EXPIRED" -> "onay süresi doldu";
            case "CONTRADICTION" -> "çelişen bir parçaya dayanıyor";
            default -> null;
        };
        String text = reason == null ? title : title + " (" + reason + ")";
        return action == HookAction.FLAG ? "İnceleme: " + text : text;
    }

    private static String keywords(List<String> keywords) {
        return keywords.isEmpty() ? "ortak sözcük yok" : String.join(", ", keywords);
    }

    private static String trend(Cluster cluster) {
        return switch (cluster.trend()) {
            case NEW -> "; bu küme yeni";
            case GROWING -> String.format(TR, "; önceki çalışmada %d soruydu, büyüyor", cluster.previousTurns());
            case SHRINKING -> String.format(TR, "; önceki çalışmada %d soruydu, küçülüyor", cluster.previousTurns());
            case STABLE -> "";
        };
    }

    private static String name(String collectionName) {
        return collectionName == null ? "adı bilinmeyen alan" : collectionName;
    }

    static String reason(FeedbackReason reason) {
        if (reason == null) return "gerekçe yok";
        return switch (reason) {
            case WRONG_SOURCE -> "yanlış kaynak";
            case INCORRECT_OR_MISSING -> "yanlış ya da eksik";
            case OUTDATED -> "güncel değil";
            case OTHER -> "diğer";
        };
    }

    private static String recommendation(FeedbackReason reason) {
        if (reason == FeedbackReason.OUTDATED) {
            return "Bu alandaki belgelerin güncel sürümlerini yükleyin ve eskilerini kaldırın.";
        }
        if (reason == FeedbackReason.INCORRECT_OR_MISSING) {
            return "Sık sorulan konuları kapsayan belgeleri tamamlayın; doğru cevapları onaylı bilgi kartına çevirin.";
        }
        if (reason == FeedbackReason.WRONG_SOURCE) {
            return "Benzer içerikli belgeleri ayırt edici başlıklarla düzenleyin ya da birleştirin.";
        }
        return "Geri bildirimleri bu alanın sahibiyle gözden geçirin.";
    }
}
