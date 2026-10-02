package com.verso.platform.observability.logging;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The single gate for free text that must reach a log line (reference 8.4). Log lines are built from allowlisted
 * fields (code=, reason=, outcome=, exceptionType=, technical ids); any text that comes from a user, a document or a
 * provider is written only after passing through this class. Parameterized logging is not sanitization: a value
 * inside {} is still raw text.
 *
 * <p>Pattern-based redaction is a last line of defence, not a licence to log content: document text, file names and
 * questions are never logged at all (llm-rules 2.1), which is why the global handler logs exception types only.
 *
 * <p>Ported from the reference skeleton (Ek B) and hardened after two review rounds (2026-10-02): any key that
 * contains a secret word (camelCase and prefixes included, quoted values with spaces included), authorization
 * schemes, cookies, IPv4/IPv6 (compressed forms included), URL credentials and query strings of any scheme are
 * redacted. Input is cut before any pattern runs, at a separator so no half token survives (bounded cost against
 * crafted input). Cause chains are walked with cycle and depth limits.
 *
 * <p>Known limits, by design of pattern matching: free-form personal data (names, file names, addresses, plates)
 * is not recognised. Such text must not be passed here at all; production log lines carry types and ids only.
 */
public final class SensitiveLogSanitizer {

    static final int MAX_LENGTH = 240;
    /** Patterns only ever see this much text, so their cost is bounded regardless of the input size. */
    static final int MAX_INPUT = 4 * MAX_LENGTH;
    private static final int MAX_CAUSE_DEPTH = 32;
    private static final String REDACTED = "[REDACTED]";

    /** Unicode line and paragraph separators, NEL, terminal escapes and other control characters (log forging). */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}\\u2028\\u2029]");
    /**
     * An Authorization header (any key containing it) with any scheme: the scheme word and the credential after it
     * are redacted. Bearer and Basic used to be the only schemes known; "DPoP tok" kept the token (review B24).
     */
    private static final Pattern AUTH_HEADER = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])([A-Za-z0-9_.-]{0,40}?authorization\"?\\s*[=:]\\s*\"?)"
                    + "(?:[A-Za-z][A-Za-z0-9_-]{0,20}\\s+)?[^\\s\",;}\\]]{1,512}");
    /** Authorization schemes followed by a credential of any length, wherever they appear. */
    private static final Pattern AUTH_SCHEME = Pattern.compile("(?i)\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]+");
    /**
     * Any key containing a secret word (accessToken, client_secret, db_password, Cookie, privateKey, ...) in k=v,
     * k: v or JSON ("k":"v"). A quoted value is redacted up to its closing quote (escaped quotes included), an unquoted
     * one up to the next separator. "pass" also covers passport numbers, which are personal data anyway.
     */
    private static final Pattern SECRET_FIELD = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])([A-Za-z0-9_.-]{0,40}?(?:token|pass|pwd|pw|secret|otp|ciphertext|salt|pepper"
                    + "|private[_-]?key|api[_-]?key|cookie|session|credential)[A-Za-z0-9_.-]{0,40})"
                    + "(\"?\\s*[=:]\\s*)(\"(?:[^\"\\\\]|\\\\.){0,512}\"|[^\\s,;&\"}\\]]{1,512})");
    /** scheme://[user]:password@host -> scheme://[REDACTED]@host (the user may be empty, the password may hold '@'). */
    private static final Pattern URL_CREDENTIALS =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,20}://)[^\\s/@:]{0,256}:[^\\s/]{1,256}@");
    /** Query strings may carry search terms or identifiers: everything after '?' in a URL of any scheme is dropped. */
    private static final Pattern URL_QUERY =
            Pattern.compile("(?i)\\b([a-z][a-z0-9+.-]{1,20}://[^\\s?#]{1,512})\\?[^\\s#]*");
    private static final Pattern IPV4 =
            Pattern.compile("\\b(?:(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\.){3}(?:25[0-5]|2[0-4]\\d|1?\\d?\\d)\\b");
    /** Full form with 3+ colons (clock times have 2) and compressed forms with "::" (2001:db8::1, ::1, fe80::1). */
    private static final Pattern IPV6 = Pattern.compile("(?i)(?<![0-9a-f:])(?:(?:[0-9a-f]{1,4}:){3,7}[0-9a-f]{1,4}"
            + "|(?:[0-9a-f]{1,4}:){1,7}:(?:[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})?"
            + "|::[0-9a-f]{1,4}(?::[0-9a-f]{1,4}){0,6})(?![0-9a-f:])");
    /** Long base64/hex blocks (JWT, ciphertext, keys) of 32+ characters. */
    private static final Pattern LONG_OPAQUE = Pattern.compile("[A-Za-z0-9+/_\\-]{32,}={0,2}");
    /** Bounded local and domain parts (RFC 5321 limits) keep matching linear on long runs of address characters. */
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9.-]{1,253}\\.[A-Za-z]{2,24}");
    /** Digit runs of 10-15 characters such as +90 555 123 45 67; ISO dates (2026-10-02 13:00) are excluded. */
    private static final Pattern PHONE =
            Pattern.compile("(?<![\\d-])(?!\\d{4}-\\d{2}-\\d{2})\\+?\\d[\\d\\s().-]{8,18}\\d");
    /** Separators at which a cut input may end without leaving half a token visible. */
    private static final Pattern SEPARATOR = Pattern.compile("[\\s,;&]");
    private static final Pattern DIGITS = Pattern.compile("\\D");

    private SensitiveLogSanitizer() {}

    /**
     * Removes CR/LF (log injection), redacts credentials, secret fields, URL credentials and queries, IP addresses and
     * opaque blocks, masks e-mail and phone, and cuts the result at 240 characters.
     */
    public static String sanitize(String raw) {
        if (raw == null) return "";
        boolean cut = raw.length() > MAX_INPUT;
        String s = cut ? cutAtSeparator(raw) : raw;
        s = CONTROL.matcher(s).replaceAll(" ");
        s = URL_CREDENTIALS.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + REDACTED + "@"));
        s = URL_QUERY.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + "?" + REDACTED));
        s = AUTH_HEADER.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + REDACTED));
        s = AUTH_SCHEME.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + " " + REDACTED));
        s = SECRET_FIELD.matcher(s).replaceAll(m -> Matcher.quoteReplacement(m.group(1) + m.group(2)
                + (m.group(3).startsWith("\"") ? "\"" + REDACTED + "\"" : REDACTED)));
        s = EMAIL.matcher(s).replaceAll(m -> Matcher.quoteReplacement(maskEmail(m.group())));
        s = IPV4.matcher(s).replaceAll("[IP]");
        s = IPV6.matcher(s).replaceAll("[IP]");
        s = PHONE.matcher(s).replaceAll(m -> Matcher.quoteReplacement(maskPhone(m.group())));
        s = LONG_OPAQUE.matcher(s).replaceAll(Matcher.quoteReplacement(REDACTED));
        return s.length() > MAX_LENGTH || cut ? s.substring(0, Math.min(s.length(), MAX_LENGTH)) + "..." : s;
    }

    /** Cuts at the last separator before MAX_INPUT so a token, address or number is never left half visible. */
    private static String cutAtSeparator(String raw) {
        String head = raw.substring(0, MAX_INPUT);
        int last = -1;
        Matcher m = SEPARATOR.matcher(head);
        while (m.find()) last = m.start();
        return last >= MAX_INPUT / 2 ? head.substring(0, last) : head.substring(0, MAX_INPUT / 2);
    }

    /** Keeps the country code (or first digit) and the last two digits: +905551234567 -> +90********67. */
    public static String maskPhone(String phone) {
        if (phone == null) return "";
        String digits = DIGITS.matcher(phone).replaceAll("");
        if (digits.length() < 6) return "***";
        int keepHead = phone.startsWith("+") ? 2 : 1;
        String head = (phone.startsWith("+") ? "+" : "") + digits.substring(0, keepHead);
        return head + "*".repeat(digits.length() - keepHead - 2) + digits.substring(digits.length() - 2);
    }

    /** jane.doe@example.com -> j***@e***.com (the TLD stays). */
    public static String maskEmail(String email) {
        if (email == null) return "";
        int at = email.indexOf('@');
        if (at < 1) return "***";
        String local = email.substring(0, at);
        String domain = email.substring(at + 1);
        int dot = domain.lastIndexOf('.');
        String tld = dot > 0 ? domain.substring(dot) : "";
        return local.charAt(0) + "***@" + (domain.isEmpty() ? "" : domain.charAt(0)) + "***" + tld;
    }

    /** Short sha256 fingerprint (12 hex) to correlate a token without logging it. */
    public static String tokenFingerprint(String token) {
        if (token == null || token.isEmpty()) return "";
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(hash, 0, 6);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /** Simple name of the deepest cause; safe against cyclic cause chains (Throwable.initCause allows them). */
    public static String rootCauseType(Throwable t) {
        Throwable root = rootCause(t);
        return root == null ? "" : root.getClass().getSimpleName();
    }

    /**
     * Root-cause type plus sanitized message, for text whose structure is known (e.g. a provider error code line).
     * Never use it for anything that may contain document text, a question or a file name: those are not logged in
     * any form (llm-rules 2.1). Production code paths in phase 1 do not call it.
     */
    public static String safeExceptionSummary(Throwable t) {
        Throwable root = rootCause(t);
        if (root == null) return "";
        String msg = root.getMessage() == null ? "" : ": " + sanitize(root.getMessage());
        return root.getClass().getSimpleName() + msg;
    }

    private static Throwable rootCause(Throwable t) {
        if (t == null) return null;
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable root = t;
        int depth = 0;
        while (root.getCause() != null && seen.add(root) && depth++ < MAX_CAUSE_DEPTH) {
            if (seen.contains(root.getCause())) break;
            root = root.getCause();
        }
        return root;
    }
}
