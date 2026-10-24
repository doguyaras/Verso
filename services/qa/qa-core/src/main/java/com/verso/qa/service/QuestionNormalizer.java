package com.verso.qa.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Equal questions for the k-anonymous "not found" list (ADR-0023): Unicode NFKC, Turkish lower case ("İzin" and
 * "izin" are one question), single spaces, no control or bidi characters, no trailing punctuation. The admin sees this normalised form, never one
 * user's own spelling: every account in a group typed the same normalised text.
 */
public final class QuestionNormalizer {

    private static final Locale TURKISH = Locale.forLanguageTag("tr");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** Control characters and bidirectional overrides: never shown to an admin, they could disguise the text. */
    private static final Pattern UNSAFE = Pattern.compile("[\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");
    private static final Pattern TRAILING_PUNCTUATION = Pattern.compile("[\\p{P}\\s]+$");

    private QuestionNormalizer() {}

    public static String normalize(String question) {
        String text = Normalizer.normalize(question, Normalizer.Form.NFKC).toLowerCase(TURKISH);
        text = UNSAFE.matcher(text).replaceAll(" ");
        text = SPACES.matcher(text).replaceAll(" ").strip();
        return TRAILING_PUNCTUATION.matcher(text).replaceAll("");
    }

    /** SHA-256 of the normalised question (32 bytes): the grouping key, stored next to the text it is derived from. */
    public static byte[] hash(String question) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(normalize(question).getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
