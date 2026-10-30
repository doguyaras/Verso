package com.verso.qa.service.gateway;

import static com.verso.qa.service.gateway.TurkishLexicon.lower;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds Turkish postal addresses (ADR-0065) by their grammar: a chain of address parts joined by spaces, commas or a
 * line break. Parts: a place (mahalle, cadde, sokak, bulvar, apartman, site) with its name before it, a door number
 * ({@code No:12}, {@code No: 12/3}), floor ({@code Kat:3}, {@code K.2}), flat ({@code D:5}, {@code Daire 5}), block,
 * postal code (five digits, 01–81 first), district and province ({@code Çankaya/Ankara}, {@code Kadıköy İstanbul}).
 *
 * <p>An address is a chain with a place and a detail ("Atatürk Cad. No:12"), two places ("Kızılay Mah. Atatürk
 * Bulvarı"), or, without a place, two details and a district or province ("No:12 D:5 Çankaya/Ankara"). A street or
 * a city mentioned on its own ("Atatürk Caddesi'nde trafik", "Ankara ofisi") is a place, not a person's address, and is
 * left alone. A suffix after an apostrophe ("Ankara'da") stays outside the match.
 */
final class TurkishAddresses {

    /** Possessive inside the word: a long word without a place word after it fails once, not once per letter. */
    private static final String NAME_WORD = "[\\p{L}\\p{N}][\\p{L}\\p{M}\\p{N}]*+\\.?";
    private static final Pattern NAME_WORD_PATTERN = Pattern.compile(NAME_WORD);
    private static final String END ="(?![\\p{L}\\p{N}])";
    private static final String PLACE_WORD = "(?:mahallesi|mahalle|mah\\.|mh\\.|caddesi|cadde|cad\\.|cd\\.|sokağı|sokak"
            + "|sok\\.|sk\\.|bulvarı|bulvar|blv\\.|bul\\.|apartmanı|apt\\.|apt|sitesi|plaza|plazası|rezidans|konutları"
            + "|evleri|iş merkezi)";
    /** A place: one to four name words, then the place word. */
    private static final Pattern PLACE = Pattern.compile("(?<![\\p{L}\\p{M}\\p{N}])((?:" + NAME_WORD + "[ \\t]+){1,4})(" + PLACE_WORD + ")"
            + "(?:(?<=\\.)|" + END + ")", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern NUMBER = Pattern.compile("(?<![\\p{L}])(?:no|numara|nu)[ \\t]*[:.]?[ \\t]*\\d{1,5}"
            + "(?:[ \\t]*[/\\-][ \\t]*[\\dA-Za-z]{1,4})?" + END, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FLOOR = Pattern.compile("(?:kat[ \\t]*[:.]?[ \\t]*-?\\d{1,3}|k[ \\t]*[:.][ \\t]*-?\\d{1,3}"
            + "|\\d{1,2}\\.[ \\t]*kat)" + END, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern FLAT = Pattern.compile("(?:daire[ \\t]*[:.]?[ \\t]*\\d{1,4}|d[ \\t]*[:.][ \\t]*\\d{1,4})"
            + END, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern BLOCK = Pattern.compile("(?:[\\p{Lu}\\d]{1,2}[ \\t]*blok|blok[ \\t]*[:.]?[ \\t]*"
            + "[\\p{Lu}\\d]{1,2})" + END, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern POSTCODE = Pattern.compile("(?:0[1-9]|[1-7]\\d|8[01])\\d{3}(?!\\d)");
    /** "Çankaya/Ankara", "Kadıköy / İSTANBUL", "Ankara/Çankaya": one side must be a province. */
    private static final Pattern DISTRICT_SLASH = Pattern.compile("(\\p{L}+(?:[ \\t]\\p{L}+)?)[ \\t]*/[ \\t]*(\\p{L}+)"
            + END);
    /** A capitalised district word and the province after it: "Çankaya Ankara", "Kadıköy, İstanbul". */
    private static final Pattern DISTRICT_PROVINCE = Pattern.compile("(\\p{Lu}\\p{Ll}+)[ \\t]*[,\\-]?[ \\t]*(\\p{L}+)"
            + END);
    private static final Pattern WORD = Pattern.compile("\\p{L}+" + END);
    private static final Pattern SEPARATOR = Pattern.compile("[ \\t]*(?:[,;][ \\t]*|[ \\t]-[ \\t]*|\\r?\\n[ \\t]*)?");
    private static final Pattern KEY_NOISE = Pattern.compile("[\\s,.:;/\\-]+");

    /** Leading capitalised words that introduce an address rather than name it ("Adres: …", "Ofisimiz …"). */
    private static final Set<String> LEAD_IN = Set.of("adres", "adresi", "adresimiz", "adresim", "ofis", "ofisimiz",
            "şube", "şubemiz", "merkez", "merkezimiz", "depo", "depomuz", "teslimat", "fatura", "konum", "yer", "bina",
            "binası", "tesis", "fabrika", "toplantı", "kargo", "ve", "ile", "için", "olan", "yeni", "eski", "ikamet",
            "ev", "evi", "iş", "işyeri", "yeri", "tebligat", "kurye", "numune", "numuneler", "malzeme", "malzemeler",
            "ürün", "ürünler", "donanım", "cihaz", "cihazlar", "personel", "servis", "paket", "sevkiyat", "iade",
            "gönderi", "gönderim", "tedarikçi", "müşteri", "çalışan");

    private enum Kind { PLACE, DETAIL, AREA }

    private record Part(int start, int end, Kind kind) {
    }

    private TurkishAddresses() {
    }

    static void find(String text, List<TurkishPii.Match> out) {
        List<int[]> starts = new ArrayList<>();
        Matcher place = PLACE.matcher(text);
        while (place.find()) {
            int start = trimLeadIn(text, place.start(1), place.end(1));
            starts.add(new int[] {start, place.end()});
        }
        Matcher number = NUMBER.matcher(text);
        while (number.find()) starts.add(new int[] {number.start(), number.end()});
        starts.sort(Comparator.comparingInt(a -> a[0]));
        int coveredTo = -1;
        for (int[] s : starts) {
            if (s[0] < coveredTo) continue;
            List<Part> chain = chain(text, s[0]);
            if (chain.isEmpty()) continue;
            int end = chain.getLast().end();
            // A start inside a rejected chain gives a tail of it, which has fewer parts and is rejected too: skipping
            // it keeps the scan linear ("No:1 No:1 No:1 …" would otherwise rebuild the chain from every number).
            coveredTo = end;
            if (!address(chain)) continue;
            out.add(new TurkishPii.Match(s[0], end, TurkishPii.Type.ADRES, key(text.substring(s[0], end))));
        }
    }

    /** The parts from {@code start} on, each after a separator, until nothing more fits. */
    private static List<Part> chain(String text, int start) {
        List<Part> parts = new ArrayList<>();
        int at = start;
        while (at < text.length()) {
            int from = at;
            if (!parts.isEmpty()) {
                Matcher sep = SEPARATOR.matcher(text).region(at, text.length());
                if (sep.lookingAt()) from = sep.end();
            }
            Part next = part(text, from, parts.isEmpty());
            if (next == null || next.end() <= from) break;
            parts.add(next);
            at = next.end();
        }
        return parts;
    }

    private static Part part(String text, int at, boolean first) {
        Matcher m = PLACE.matcher(text).region(at, text.length());
        if (m.lookingAt()) {
            // A later place must start with its own name: "… Çankaya/Ankara ve İnönü Sok." is two addresses, the
            // connecting words are not a place's name.
            if (!first && trimLeadIn(text, m.start(1), m.end(1)) != at) return null;
            return new Part(at, m.end(), Kind.PLACE);
        }
        for (Pattern detail : List.of(NUMBER, FLOOR, FLAT, BLOCK)) {
            m = detail.matcher(text).region(at, text.length());
            if (m.lookingAt()) return new Part(at, m.end(), Kind.DETAIL);
        }
        if (first) return null;
        m = POSTCODE.matcher(text).region(at, text.length());
        if (m.lookingAt()) return new Part(at, m.end(), Kind.DETAIL);
        m = DISTRICT_SLASH.matcher(text).region(at, text.length());
        if (m.lookingAt() && (province(m.group(2)) || province(lastWord(m.group(1))))) {
            return new Part(at, m.end(), Kind.AREA);
        }
        m = DISTRICT_PROVINCE.matcher(text).region(at, text.length());
        if (m.lookingAt() && province(m.group(2)) && !province(m.group(1))) return new Part(at, m.end(), Kind.AREA);
        m = WORD.matcher(text).region(at, text.length());
        if (m.lookingAt() && province(m.group())) return new Part(at, m.end(), Kind.AREA);
        return null;
    }

    private static boolean address(List<Part> chain) {
        long places = chain.stream().filter(p -> p.kind() == Kind.PLACE).count();
        long details = chain.stream().filter(p -> p.kind() == Kind.DETAIL).count();
        long areas = chain.stream().filter(p -> p.kind() == Kind.AREA).count();
        if (places >= 1) return details >= 1 || places >= 2;
        return details >= 2 && areas >= 1;
    }

    /**
     * Drops the words before a place's name that are not part of it: lower-case words when a capitalised or numbered
     * word follows ("kargo Atatürk Cad."), and lead-ins ("Adres:", "Ofisimiz"). In an all lower-case text the last two
     * words are kept ("atatürk cad.").
     */
    private static int trimLeadIn(String text, int from, int to) {
        List<int[]> words = new ArrayList<>();
        Matcher w = NAME_WORD_PATTERN.matcher(text).region(from, to);
        while (w.find()) words.add(new int[] {w.start(), w.end()});
        int keep = 0;
        boolean anyNamed = false;
        for (int k = 0; k < words.size(); k++) {
            int c = text.codePointAt(words.get(k)[0]);
            if (Character.isUpperCase(c) || Character.isDigit(c)) anyNamed = true;
        }
        if (anyNamed) {
            for (int k = 0; k < words.size(); k++) {
                int c = text.codePointAt(words.get(k)[0]);
                if (!(Character.isUpperCase(c) || Character.isDigit(c))) keep = k + 1;
            }
        } else {
            keep = Math.max(0, words.size() - 2);
        }
        while (keep < words.size() - 1) {
            String word = lower(text.substring(words.get(keep)[0], words.get(keep)[1])).replace(".", "");
            if (!LEAD_IN.contains(word)) break;
            keep++;
        }
        return words.isEmpty() ? from : words.get(Math.min(keep, words.size() - 1))[0];
    }

    private static boolean province(String word) {
        return word != null && TurkishLexicon.PROVINCE.contains(lower(word));
    }

    private static String lastWord(String words) {
        int space = words.lastIndexOf(' ');
        return space < 0 ? words : words.substring(space + 1);
    }

    /** One value however it is spaced or punctuated: "Atatürk Cad. No:12" and "atatürk cad no 12" are the same. */
    static String key(String address) {
        return KEY_NOISE.matcher(lower(address)).replaceAll(" ").strip();
    }
}
