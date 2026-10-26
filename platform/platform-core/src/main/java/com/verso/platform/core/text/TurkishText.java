package com.verso.platform.core.text;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Turkish text folding and the code token shared by the groundedness check (ADR-0047) and the system model
 * (ADR-0054). One definition on purpose: a code the answer check reads as "SAT-E058" must be the same code the system
 * model indexes, or a lookup and a check would disagree about what a code is.
 */
public final class TurkishText {

    public static final Locale TURKISH = Locale.forLanguageTag("tr");

    /**
     * A code: a token of capitals and digits with at least one of each, hyphens inside (P1, SAT-E058, WMS-204,
     * A-03-02-B), not glued to a letter or digit. A regex fragment without a named group, so callers can wrap it.
     */
    public static final String CODE_REGEX =
            "(?<![\\p{L}\\p{N}])(?=[\\p{Lu}\\p{N}-]*\\p{N})(?=[\\p{Lu}\\p{N}-]*\\p{Lu})[\\p{Lu}\\p{N}]+"
                    + "(?:-[\\p{Lu}\\p{N}]+)*(?![\\p{L}\\p{N}])";

    private static final Pattern MARKS = Pattern.compile("\\p{M}+");

    private TurkishText() {
    }

    /**
     * Lower case by Turkish rules, dotless i folded to i, diacritics removed: "İADE", "iade" and "IADE" read alike,
     * and so do "Dördüncü" and "dorduncu" (documents lose their diacritics in bad PDFs, OCR and ASCII exports).
     */
    public static String fold(String text) {
        String lower = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(TURKISH).replace('ı', 'i');
        return MARKS.matcher(Normalizer.normalize(lower, Normalizer.Form.NFD)).replaceAll("");
    }

    /** {@link #fold} in upper case (ASCII rules after folding): the key of a code or an abbreviation ("İK-3" is "IK-3"). */
    public static String foldUpper(String text) {
        return fold(text).toUpperCase(Locale.ROOT);
    }
}
