package com.verso.qa.service;

import com.verso.document.api.dto.SystemEntityDetail;
import com.verso.document.api.enums.SystemEntityType;
import com.verso.platform.core.text.TurkishText;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The cheap test before any decomposition call (ADR-0055): no model, no network. A question is split only when it
 * compares ("fark", "karşılaştır", "hem … hem", "X ile Y arasında"), asks two things at once ("Kaç gün ve kim
 * onaylar?", two question marks), or names two or more distinct systems or codes of the system model (ADR-0054). A
 * short question never is. Words are compared after Turkish folding, so "FARKI", "farkı" and "farki" read alike.
 */
public final class ReasoningGate {

    /** Why the question is decomposed; NONE: it is answered in a single pass. */
    public enum Reason {
        COMPARISON, MULTI_PART, ENTITIES, NONE
    }

    /** Questions of fewer words are never decomposed: there is nothing to split. */
    static final int MIN_WORDS = 5;

    private static final Pattern WORD_SEPARATORS = Pattern.compile("[^\\p{L}\\p{N}]+");
    /** Word prefixes (folded) that compare two things. */
    private static final List<String> COMPARISON_PREFIXES = List.of("fark", "karsilastir", "kiyas", "mukayese");
    /** Folded words of a relation between two named things; only with "ile" or "ve" in the same question. */
    private static final List<String> BETWEEN_PREFIXES = List.of("arasinda", "arasindaki", "iliski");
    /** Question words (folded); two of them joined by "ve" or a comma ask two things. */
    private static final Set<String> QUESTION_WORDS = Set.of("ne", "neler", "nedir", "nelerdir", "kac", "kim", "kime",
            "kimdir", "kimin", "nasil", "hangi", "hangisi", "nerede", "nereye", "neden", "nicin", "niye");

    private ReasoningGate() {
    }

    /** The lexical part of the gate; NONE when no word decides. */
    public static Reason lexical(String question) {
        List<String> words = words(question);
        if (words.size() < MIN_WORDS) return Reason.NONE;
        int hem = 0;
        boolean joined = false;
        boolean between = false;
        for (String word : words) {
            for (String prefix : COMPARISON_PREFIXES) {
                if (word.startsWith(prefix)) return Reason.COMPARISON;
            }
            if (word.equals("hem")) hem++;
            if (word.equals("ile") || word.equals("ve")) joined = true;
            for (String prefix : BETWEEN_PREFIXES) {
                if (word.startsWith(prefix)) between = true;
            }
        }
        if (hem >= 2 || (between && joined)) return Reason.COMPARISON;
        if (multiPart(question, words)) return Reason.MULTI_PART;
        return Reason.NONE;
    }

    /**
     * The whole gate: the lexical part first; otherwise two or more distinct SYSTEM or CODE entities the question names
     * (looked up only when the lexical part is undecided, and only for a long enough question).
     */
    public static Reason decide(String question, List<SystemEntityDetail> entities) {
        Reason lexical = lexical(question);
        if (lexical != Reason.NONE) return lexical;
        if (words(question).size() < MIN_WORDS) return Reason.NONE;
        return distinctSystemsAndCodes(entities) >= 2 ? Reason.ENTITIES : Reason.NONE;
    }

    /** True when the lexical part cannot decide but a long question might still name two systems. */
    public static boolean worthLookingUp(String question) {
        return lexical(question) == Reason.NONE && words(question).size() >= MIN_WORDS;
    }

    /** Distinct SYSTEM and CODE keys among {@code entities} ("WMS" and "ERP"; "WMS-204" and "WMS" are two). */
    public static int distinctSystemsAndCodes(List<SystemEntityDetail> entities) {
        if (entities == null) return 0;
        Set<String> keys = new HashSet<>();
        for (SystemEntityDetail entity : entities) {
            if (entity.type() == SystemEntityType.SYSTEM || entity.type() == SystemEntityType.CODE) {
                keys.add(entity.type() + ":" + entity.key());
            }
        }
        return keys.size();
    }

    /** Two questions in one: two question marks with words between, or two question words joined by "ve" or ",". */
    private static boolean multiPart(String question, List<String> words) {
        String trimmed = question.strip();
        int first = trimmed.indexOf('?');
        if (first >= 0 && first < trimmed.length() - 1 && trimmed.indexOf('?', first + 1) > first + 1
                && !WORD_SEPARATORS.matcher(trimmed.substring(first + 1)).replaceAll("").isEmpty()) {
            return true;
        }
        int questionWords = 0;
        boolean connective = trimmed.contains(",");
        for (String word : words) {
            if (QUESTION_WORDS.contains(word)) questionWords++;
            if (word.equals("ve") || word.equals("ayrica")) connective = true;
        }
        return questionWords >= 2 && connective;
    }

    private static List<String> words(String question) {
        if (question == null || question.isBlank()) return List.of();
        return WORD_SEPARATORS.splitAsStream(TurkishText.fold(question)).filter(w -> !w.isEmpty()).toList();
    }
}
