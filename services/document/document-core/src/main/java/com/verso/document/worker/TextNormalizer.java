package com.verso.document.worker;

import java.util.regex.Pattern;

/**
 * The one text clean-up every format goes through before it is stored and chunked: line endings unified, control
 * characters (PostgreSQL text cannot hold NUL) and runs of spaces collapsed, at most one blank line in a row.
 */
final class TextNormalizer {

    /** Control characters except tab and newline. */
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}&&[^\\t\\n]]");
    private static final Pattern SPACES = Pattern.compile("[ \\t\\x0B\\f\\u00A0]+");
    private static final Pattern BLANK_LINES = Pattern.compile("\\n\\s*\\n+");

    private TextNormalizer() {}

    static String normalize(String text) {
        String cleaned = CONTROL.matcher(text.replace("\r\n", "\n").replace('\r', '\n')).replaceAll(" ");
        cleaned = SPACES.matcher(cleaned).replaceAll(" ");
        cleaned = BLANK_LINES.matcher(cleaned).replaceAll("\n\n");
        return cleaned.strip();
    }
}
