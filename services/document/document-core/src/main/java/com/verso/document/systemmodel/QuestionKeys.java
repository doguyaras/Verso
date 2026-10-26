package com.verso.document.systemmodel;

import com.verso.platform.core.text.TurkishText;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The entity keys a question or a lookup query can name (ADR-0054): every code in the AnchorCheck shape and every word
 * folded to upper case (a code, an abbreviation or a system acronym written in any case: "fefo nedir", "İk-3"), plus
 * the whole query folded (a system or screen name of several words). Bounded, so a long question costs one small
 * indexed lookup.
 */
public final class QuestionKeys {

    public static final int MAX_KEYS = 32;
    private static final Pattern CODE = Pattern.compile(TurkishText.CODE_REGEX);
    private static final Pattern WORD = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}&-]{1,39}");

    private QuestionKeys() {
    }

    public static List<String> of(String text) {
        if (text == null || text.isBlank()) return List.of();
        Set<String> keys = new LinkedHashSet<>();
        Matcher code = CODE.matcher(text);
        while (code.find() && keys.size() < MAX_KEYS) keys.add(TurkishText.foldUpper(code.group()));
        Matcher word = WORD.matcher(text);
        while (word.find() && keys.size() < MAX_KEYS) {
            String token = word.group();
            // Strip a hyphen at the end ("WMS-" in "WMS-'de"); keep inner hyphens of codes.
            while (token.endsWith("-")) token = token.substring(0, token.length() - 1);
            if (token.length() >= 2) keys.add(TurkishText.foldUpper(token));
        }
        String whole = TurkishText.fold(text.strip().replaceAll("\\s+", " "));
        if (keys.size() < MAX_KEYS && whole.length() <= 200) keys.add(whole);
        return List.copyOf(keys);
    }
}
