package com.verso.qa.service;

import com.verso.qa.service.SentenceSplitter.Span;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model's own refusal in its own words (ADR-0064). The prompt asks for the fixed not-found sentence, but a small
 * model often writes its own ("Belgelerde … hakkında bilgi bulunmamaktadır [4]."), cites a passage anyway, or goes on
 * with "Ancak, …" and a loosely related rule. Such an answer must not reach the reader as a sourced answer.
 *
 * <p>A clause is a refusal when it ends in a negated "is (not) there" predicate ("bulunmamaktadır", "yer almıyor",
 * "belirtilmemiştir", "bilgi yok", …) and speaks about the information or the documents: an information noun ("bilgi",
 * "cevap", "açıklama", "düzenleme", …) or a document place ("belgelerde", "pasajlarda", "metinde"). "Yönetmelikte yaş
 * sınırı bulunmamaktadır" is not one: it is a substantive answer (there is no age limit), and the rule names neither.
 * The predicate must close its clause (a sentence end, punctuation, or "ancak"/"fakat"/"ama"), so "belgesi
 * bulunamadığında" or "belirtilmemiş durumlarda" never match.
 *
 * <p>Pure and allocation-light; it runs on the hot path after the chat call. Nothing is logged.
 */
final class RefusalDetector {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final Pattern MARKER = Pattern.compile("\\[\\d{1,9}]");
    // The model sometimes repeats the prompt's fences ("[[BELGE 1]] belgesinde …"); they are not words.
    private static final Pattern FENCE = Pattern.compile("\\[\\[/?BELGE \\d{1,9}]]", Pattern.CASE_INSENSITIVE);
    private static final String COPULA = "(?:tur|tır|tir|dır|dir|dur|dür|ler|lar)*";
    private static final Pattern NEGATION = Pattern.compile(
            "(?<!\\p{L})(?:bulunmamakta|bulunmuyor|bulunamadı|bulunamamış|bulunamamakta|bulunmamış"
                    + "|yer\\s+almamakta|yer\\s+almıyor|yer\\s+almamış|yer\\s+verilmemiş|yer\\s+verilmemekte"
                    + "|belirtilmemiş|belirtilmemekte|belirtilmiyor|rastlanmamış|rastlanmadı|rastlanamadı"
                    + "|içermemekte|içermiyor|bahsedilmemiş|bahsedilmemekte|bahsedilmiyor|değinilmemiş"
                    + "|söz\\s+edilmemiş|söz\\s+edilmemekte|açıklanmamış|açıklanmamakta|tanımlanmamış"
                    + "|mevcut\\s+değil|bilgi(?:si|ye)?\\s+yok)" + COPULA
                    // The predicate closes its clause: a sentence end, punctuation, or a contrast that opens the next.
                    + "(?=\\s*(?:$|[.;,:!?)…]|(?:ancak|fakat|ama|yalnızca|sadece)(?!\\p{L})))");
    // What a refusal speaks about: the information asked for, or the documents it was looked for in.
    private static final Pattern ABOUT = Pattern.compile(
            "(?<!\\p{L})(?:bilgi|cevab|cevap|yanıt|açıklama|ifade|detay|ayrıntı|hüküm|hükm|düzenleme"
                    + "|veri(?:ler|si)?(?!\\p{L})|belgelerde|belgede|belgelerin|pasajlarda|pasajda|pasajların"
                    + "|metinde|metinlerde|kaynaklarda|kaynakta|belge\\s+\\d)");
    private static final Pattern LEADING_JOINERS = Pattern.compile(
            "^[\\s,;:.…-]*(?:(?:ancak|fakat|ama|yalnızca|sadece|bununla birlikte)(?!\\p{L})[\\s,;:]*)?");
    // Where a clause may start before the predicate: punctuation, or a conjunction that opens the negated part.
    private static final Pattern CLAUSE_BOUNDARY = Pattern.compile(
            "[,;:]|(?<!\\p{L})(?:ve|ancak|fakat|ama)(?!\\p{L})");
    // A clause that states something closes with a predicate: a copula ("gündür"), a passive or aorist verb
    // ("verilir", "oluşur", "gerekir"), a past or progressive form. "14 güne kadar," or "2024 yılı için," is not one.
    private static final Pattern PREDICATE_END = Pattern.compile(
            "(?:[dt][ıiuü]r|[lnşkrç][ıiuü]r|m[ae]z|m[ıiuü]ş|yor|[dt][ıiuü])$");

    /** A part of the answer: a refusal clause, or the text around it (markers and fences removed). */
    record Segment(boolean refusal, String text) {
    }

    /** The refusal clause of one sentence: where it begins, and where its predicate ends. */
    private record Refusal(int clauseStart, int end) {
    }

    private RefusalDetector() {
    }

    /**
     * The answer's sentences in order, each refusal clause on its own: "X bilgi bulunmamaktadır, ancak Y [1]." is a
     * refusal segment and a content segment ("Y"). A fact stated before the negated clause of the same sentence
     * ("yıllık izin 14 gündür ve … hüküm bulunmamaktadır") is a content segment too. Empty segments are dropped.
     */
    static List<Segment> segments(String answer) {
        String text = FENCE.matcher(Normalizer.normalize(answer, Normalizer.Form.NFC)).replaceAll(" ");
        List<Segment> out = new ArrayList<>();
        for (Span span : SentenceSplitter.split(text, true)) {
            String sentence = MARKER.matcher(text.substring(span.start(), span.end())).replaceAll(" ").strip();
            Refusal refusal = refusal(sentence);
            if (refusal == null) {
                addContent(out, sentence);
                continue;
            }
            if (refusal.clauseStart() > 0) addContent(out, sentence.substring(0, refusal.clauseStart()));
            out.add(new Segment(true, sentence.substring(refusal.clauseStart(), refusal.end()).strip()));
            addContent(out, LEADING_JOINERS.matcher(sentence.substring(refusal.end())).replaceFirst(""));
        }
        return out;
    }

    /** True when the whole sentence or clause is a refusal (the test's view of one sentence). */
    static boolean isRefusal(String sentence) {
        Refusal refusal = refusal(MARKER.matcher(sentence).replaceAll(" ").strip());
        return refusal != null && refusal.clauseStart() == 0;
    }

    /** The refusal clause of {@code sentence}, or null. Lower-casing keeps the length (Turkish locale). */
    private static Refusal refusal(String sentence) {
        String lower = sentence.toLowerCase(TURKISH);
        if (lower.length() != sentence.length()) lower = sentence.toLowerCase(Locale.ROOT);
        Matcher negation = NEGATION.matcher(lower);
        if (!negation.find()) return null;
        // The information or the documents named before the predicate ("bilgi yok" names it itself).
        if (!negation.group().startsWith("bilgi") && !ABOUT.matcher(lower).region(0, negation.start()).find()) {
            return null;
        }
        return new Refusal(clauseStart(sentence, lower, negation.start(), negation.group().startsWith("bilgi")),
                negation.end());
    }

    /**
     * Where the refusal clause begins: the nearest boundary before the predicate whose clause still names the
     * information or the documents, and only when what stands before it is a statement of its own (it closes with a
     * predicate) that gives a number or a code (a fact the groundedness check can read, as in "yıllık izin 14 gündür
     * ve … hüküm bulunmamaktadır"). Otherwise the sentence start: "Belgelerde, bu konuda bilgi bulunmamaktadır" and
     * "Belgelerde 2024 yılı için, … bilgi bulunmamaktadır" have nothing to keep before their comma.
     */
    private static int clauseStart(String sentence, String lower, int negationStart, boolean selfNaming) {
        List<Integer> boundaries = new ArrayList<>();
        Matcher boundary = CLAUSE_BOUNDARY.matcher(lower).region(0, negationStart);
        while (boundary.find()) boundaries.add(boundary.start());
        for (int k = boundaries.size() - 1; k >= 0; k--) {
            int start = boundaries.get(k);
            if (!selfNaming && !ABOUT.matcher(lower).region(start, negationStart).find()) continue;
            String before = lower.substring(0, start).strip();
            if (!PREDICATE_END.matcher(before).find()) return 0;
            for (AnchorCheck.Token token : AnchorCheck.tokens(sentence.substring(0, start), true)) {
                if (token.anchor() != null) return start;
            }
            return 0;
        }
        return 0;
    }

    private static void addContent(List<Segment> out, String text) {
        String stripped = text.strip();
        if (stripped.codePoints().anyMatch(Character::isLetterOrDigit)) out.add(new Segment(false, stripped));
    }
}
