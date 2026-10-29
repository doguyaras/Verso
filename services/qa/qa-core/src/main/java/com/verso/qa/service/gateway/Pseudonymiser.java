package com.verso.qa.service.gateway;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The placeholders of ONE model call (ADR-0059): {@link #mask} replaces every {@link TurkishPii} finding with a
 * type-tagged placeholder ({@code [TCKN_1]}, {@code [IBAN_2]}), the same value always with the same one; {@link #restore}
 * puts the values back into the answer. The mapping lives only in this object, which lives only as long as the call:
 * it is never logged, stored or audited. Only {@link #counts()} (how many distinct values per type) leaves.
 *
 * <p>Not thread-safe: one call's request is masked before it is sent and its answer restored in order; a stream's
 * chunks arrive one at a time (Reactor's serial signals).
 */
final class Pseudonymiser {

    /** A complete placeholder. Six digits are far more than one prompt can need. */
    static final Pattern PLACEHOLDER = Pattern.compile("\\[(IBAN|CARD|EMAIL|TCKN|PHONE|VKN|PLATE)_(\\d{1,6})]");
    /** The longest placeholder, "[EMAIL_999999]": a held-back tail is never longer. */
    private static final int MAX_PLACEHOLDER = 14;

    private final Map<String, String> placeholderOfValue = new HashMap<>();
    private final Map<String, String> valueOfPlaceholder = new HashMap<>();
    private final EnumMap<TurkishPii.Type, Integer> next = new EnumMap<>(TurkishPii.Type.class);

    /**
     * Placeholder-shaped text already in the prompt (a document quoting "[TCKN_1]") must not be turned into a value on
     * the way back: numbering starts after the highest such number. Call it for every text before masking any.
     */
    void reserve(String text) {
        if (text == null) return;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) {
            TurkishPii.Type type = TurkishPii.Type.valueOf(m.group(1));
            int used = Integer.parseInt(m.group(2));
            next.merge(type, used, Math::max);
        }
    }

    String mask(String text) {
        if (text == null || text.isEmpty()) return text;
        List<TurkishPii.Match> matches = TurkishPii.find(text);
        if (matches.isEmpty()) return text;
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        for (TurkishPii.Match m : matches) {
            out.append(text, at, m.start());
            String original = text.substring(m.start(), m.end());
            String placeholder = placeholderOfValue.computeIfAbsent(m.type() + ":" + m.key(), key -> {
                int number = next.merge(m.type(), 1, Integer::sum);
                String p = "[" + m.type() + "_" + number + "]";
                // The first spelling seen is the one the answer gets back.
                valueOfPlaceholder.put(p, original);
                return p;
            });
            out.append(placeholder);
            at = m.end();
        }
        out.append(text, at, text.length());
        return out.toString();
    }

    /** Placeholders of this call become their values again; any other bracketed text stays as it is. */
    String restore(String text) {
        if (text == null || text.isEmpty() || valueOfPlaceholder.isEmpty() || text.indexOf('[') < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        while (m.find()) {
            String value = valueOfPlaceholder.get(m.group());
            m.appendReplacement(out, Matcher.quoteReplacement(value == null ? m.group() : value));
        }
        m.appendTail(out);
        return out.toString();
    }

    /** Distinct values masked in this call, by type name; empty when none. Counts only, never values. */
    Map<String, Integer> counts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String key : placeholderOfValue.keySet()) {
            String type = key.substring(0, key.indexOf(':'));
            counts.merge(type, 1, Integer::sum);
        }
        return Map.copyOf(counts);
    }

    boolean masked() {
        return !placeholderOfValue.isEmpty();
    }

    /** A streamed answer's restorer: one per generation of the stream. */
    Restorer restorer() {
        return new Restorer();
    }

    /**
     * Restores a streamed answer whose placeholders may be split over chunks ("[TC", "KN_1]"): a tail that could still
     * become a placeholder is held back until the next chunk shows whether it does, and {@link #flush} at the end of
     * the stream releases whatever is left.
     */
    final class Restorer {
        private final StringBuilder pending = new StringBuilder();

        String push(String chunk) {
            if (chunk == null || chunk.isEmpty()) return chunk;
            pending.append(chunk);
            int hold = holdFrom(pending);
            String ready = pending.substring(0, hold);
            pending.delete(0, hold);
            return restore(ready);
        }

        String flush() {
            String rest = restore(pending.toString());
            pending.setLength(0);
            return rest;
        }
    }

    /** Where the tail that might still grow into a placeholder starts; the length when there is none. */
    static int holdFrom(CharSequence text) {
        int open = -1;
        for (int i = text.length() - 1; i >= 0 && i >= text.length() - MAX_PLACEHOLDER; i--) {
            if (text.charAt(i) == '[') {
                open = i;
                break;
            }
        }
        if (open < 0) return text.length();
        return couldBecomePlaceholder(text.subSequence(open + 1, text.length()).toString()) ? open : text.length();
    }

    /** Whether "[" + {@code tail} is the beginning of a placeholder (without its closing bracket). */
    static boolean couldBecomePlaceholder(String tail) {
        int underscore = tail.indexOf('_');
        String name = underscore < 0 ? tail : tail.substring(0, underscore);
        if (underscore < 0) {
            for (TurkishPii.Type type : TurkishPii.Type.values()) {
                if (type.name().startsWith(name)) return true;
            }
            return false;
        }
        String number = tail.substring(underscore + 1);
        boolean known = false;
        for (TurkishPii.Type type : TurkishPii.Type.values()) known |= type.name().equals(name);
        return known && number.length() <= 6 && number.chars().allMatch(Character::isDigit);
    }
}
