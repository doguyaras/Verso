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
    static final Pattern PLACEHOLDER = Pattern.compile("\\[(IBAN|CARD|EMAIL|TCKN|PHONE|VKN|PLATE|ADRES|AD)_(\\d{1,6})]");
    /** The longest placeholder, "[EMAIL_999999]" or "[ADRES_999999]". */
    private static final int MAX_PLACEHOLDER = 14;
    /** The longest suffix held after a name or address placeholder in a stream ("[AD_1]'ndakilerden"). */
    private static final int MAX_SUFFIX = 16;
    /** A name or address placeholder at the end of a stream chunk, maybe with the start of its suffix. */
    private static final Pattern OPEN_SUFFIX = Pattern.compile("(?:AD|ADRES)_\\d{1,6}](?:['’]\\p{L}{0,16})?");
    /** The suffix after a restored name or address: an apostrophe and letters. */
    private static final Pattern SUFFIX = Pattern.compile("(['’])(\\p{L}{1,16})(?!\\p{L})");

    private final Map<String, String> placeholderOfValue = new HashMap<>();
    private final Map<String, String> valueOfPlaceholder = new HashMap<>();
    private final EnumMap<TurkishPii.Type, Integer> next = new EnumMap<>(TurkishPii.Type.class);
    /** Placeholder-shaped text already in the prompt: never handed out, never restored. */
    private final java.util.Set<String> reserved = new java.util.HashSet<>();

    /**
     * Placeholder-shaped text already in the prompt (a document quoting "[TCKN_1]") must not be turned into a value on
     * the way back: those exact placeholders are never handed out. Numbering skips them instead of starting after the
     * highest one, so a prompt quoting "[TCKN_999999]" cannot push the numbers past what {@link #PLACEHOLDER} and the
     * stream hold know (ADR-0059 review). Call it for every text before masking any.
     */
    void reserve(String text) {
        if (text == null) return;
        Matcher m = PLACEHOLDER.matcher(text);
        while (m.find()) reserved.add(m.group());
    }

    private String nextPlaceholder(TurkishPii.Type type) {
        while (true) {
            int number = next.merge(type, 1, Integer::sum);
            // At most one skipped number per reserved placeholder: the numbers stay below the prompt's length.
            String p = "[" + type + "_" + number + "]";
            if (!reserved.contains(p)) return p;
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
                String p = nextPlaceholder(m.type());
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

    /**
     * Placeholders of this call become their values again; any other bracketed text stays as it is. A case suffix
     * after a name or address ("[AD_1]'in") is fitted to the value ("Yılmaz'ın", {@link TurkishSuffix}).
     */
    String restore(String text) {
        if (text == null || text.isEmpty() || valueOfPlaceholder.isEmpty() || text.indexOf('[') < 0) return text;
        Matcher m = PLACEHOLDER.matcher(text);
        StringBuilder out = new StringBuilder(text.length());
        int at = 0;
        while (m.find(at)) {
            out.append(text, at, m.start());
            String value = valueOfPlaceholder.get(m.group());
            at = m.end();
            if (value == null) {
                out.append(m.group());
                continue;
            }
            out.append(value);
            boolean nameOrAddress = m.group(1).equals("AD") || m.group(1).equals("ADRES");
            Matcher suffix = SUFFIX.matcher(text).region(at, text.length());
            if (nameOrAddress && suffix.lookingAt()) {
                out.append(suffix.group(1)).append(TurkishSuffix.fit(value, suffix.group(2)));
                at = suffix.end();
            }
        }
        out.append(text, at, text.length());
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

    /**
     * Where the tail that might still grow into a placeholder starts; the length when there is none. A complete name
     * or address placeholder at the very end is held too, with the letters of its suffix so far: the next chunk may
     * carry the rest of the suffix ("[AD_1]" + "'in"), which is fitted only when seen whole.
     */
    static int holdFrom(CharSequence text) {
        int open = -1;
        for (int i = text.length() - 1; i >= 0 && i >= text.length() - MAX_PLACEHOLDER - 1 - MAX_SUFFIX; i--) {
            if (text.charAt(i) == '[') {
                open = i;
                break;
            }
        }
        if (open < 0) return text.length();
        String tail = text.subSequence(open + 1, text.length()).toString();
        if (tail.length() < MAX_PLACEHOLDER && couldBecomePlaceholder(tail)) return open;
        return OPEN_SUFFIX.matcher(tail).matches() ? open : text.length();
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
