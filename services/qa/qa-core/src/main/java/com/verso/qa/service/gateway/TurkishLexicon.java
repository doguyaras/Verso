package com.verso.qa.service.gateway;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The word lists of name and address detection (ADR-0065), read once from {@code pii/tr/*.txt} on the classpath. The
 * lists are Verso's own (no third-party data). Entries are kept in Turkish lower case ({@link #lower}).
 *
 * <p>A missing list is a broken build, not a degraded mode: the class fails to initialise and the masking filter
 * throws before anything is sent (fail closed, ADR-0059).
 */
final class TurkishLexicon {

    static final Locale TR = Locale.forLanguageTag("tr");

    /** Given names that are not ordinary words: "Ayşe", "Mehmet". */
    static final Set<String> FIRST = load("ad.txt");
    /** Given names that are also words, places or months: "Deniz", "Umut", "Bahar", "Metin". */
    static final Set<String> FIRST_AMBIGUOUS = load("ad-belirsiz.txt");
    static final Set<String> SURNAME = load("soyad.txt");
    static final Set<String> PROVINCE = load("il.txt");
    /** Name-shaped phrases never masked ("Mustafa Kemal Atatürk", "Fatih Sultan Mehmet"), as lower-case tokens. */
    static final List<String[]> NOT_A_NAME = load("ad-haric.txt").stream().map(p -> p.split(" ")).toList();

    private TurkishLexicon() {
    }

    static String lower(String s) {
        return s.toLowerCase(TR);
    }

    static boolean firstName(String lower) {
        return FIRST.contains(lower) || FIRST_AMBIGUOUS.contains(lower);
    }

    /** A first name that is never an ordinary word; an ambiguous entry wins over a plain one. */
    static boolean plainFirstName(String lower) {
        return FIRST.contains(lower) && !FIRST_AMBIGUOUS.contains(lower);
    }

    /** A listed surname or one with the patronymic ending ("Karaosmanoğlu", "Hacıoğulları"). */
    static boolean surname(String lower) {
        return SURNAME.contains(lower) || (lower.length() > 5 && (lower.endsWith("oğlu") || lower.endsWith("oğulları")));
    }

    private static Set<String> load(String file) {
        String path = "pii/tr/" + file;
        try (InputStream in = TurkishLexicon.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("Missing word list " + path);
            Set<String> words = new HashSet<>();
            List<String> order = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                    String entry = line.strip();
                    if (entry.isEmpty() || entry.startsWith("#")) continue;
                    if (words.add(lower(entry))) order.add(lower(entry));
                }
            }
            if (words.isEmpty()) throw new IllegalStateException("Empty word list " + path);
            return java.util.Collections.unmodifiableSet(new java.util.LinkedHashSet<>(order));
        } catch (IOException e) {
            throw new IllegalStateException("Unreadable word list " + path, e);
        }
    }
}
