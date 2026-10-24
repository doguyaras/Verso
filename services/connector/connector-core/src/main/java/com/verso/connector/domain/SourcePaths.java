package com.verso.connector.domain;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Path rules of a file-share source (ADR-0038). Internally a path is relative to the connector's prefix, "/"-separated
 * and normalised; it is turned into an SMB path ("\"-separated, relative to the share) only at the client. Every name
 * that comes from the server is checked before it becomes part of a path, so a hostile or buggy server cannot make the
 * connector address anything outside the configured prefix ("..", separators or a drive/stream colon inside a name).
 */
public final class SourcePaths {

    /** Longest accepted prefix or relative path (well below SMB's 32 767). */
    public static final int MAX_PATH = 1024;
    private static final int MAX_NAME = 255;
    /** Windows-reserved characters, the separators, and every control character (C0, DEL, C1). */
    private static final Pattern FORBIDDEN = Pattern.compile("[\\\\/:*?\"<>|\\p{Cc}\\u202A-\\u202E\\u2066-\\u2069]");
    /** The extensions the document pipeline reads (ADR-0016); anything else is never downloaded. */
    private static final Set<String> SUPPORTED = Set.of(".pdf", ".docx", ".txt", ".md", ".markdown");

    private SourcePaths() {}

    /**
     * The configured prefix in normal form ("" for the whole share, otherwise "a/b"). Accepts "/" or "\" separators and
     * surrounding separators; refuses ".", "..", empty segments, a colon (drive letter, alternate data stream) and
     * reserved or control characters.
     *
     * @throws IllegalArgumentException with a fixed reason word, never echoing the input
     */
    public static String normalizePrefix(String raw) {
        if (raw == null || raw.isBlank()) return "";
        if (raw.length() > MAX_PATH) throw new IllegalArgumentException("PATH_TOO_LONG");
        String unified = raw.strip().replace('\\', '/');
        if (unified.startsWith("//")) throw new IllegalArgumentException("PATH_NOT_RELATIVE");
        while (unified.startsWith("/")) unified = unified.substring(1);
        while (unified.endsWith("/")) unified = unified.substring(0, unified.length() - 1);
        if (unified.isEmpty()) return "";
        for (String segment : unified.split("/", -1)) {
            if (!isSafeName(segment)) throw new IllegalArgumentException("PATH_SEGMENT_INVALID");
        }
        return unified;
    }

    /**
     * Whether a single name (a directory entry or a prefix segment) can be part of a path: not empty, not "." or "..",
     * no separator, colon, wildcard or control character, at most 255 characters, no trailing dot or space (Windows
     * strips them, so "a." and "a" would be the same file).
     */
    public static boolean isSafeName(String name) {
        if (name == null || name.isEmpty() || name.length() > MAX_NAME) return false;
        if (name.equals(".") || name.equals("..")) return false;
        if (name.endsWith(".") || name.endsWith(" ")) return false;
        return !FORBIDDEN.matcher(name).find();
    }

    /** A child of a relative path; the name must already be safe. */
    public static String child(String parent, String name) {
        if (!isSafeName(name)) throw new IllegalArgumentException("PATH_SEGMENT_INVALID");
        String path = parent.isEmpty() ? name : parent + "/" + name;
        if (path.length() > MAX_PATH) throw new IllegalArgumentException("PATH_TOO_LONG");
        return path;
    }

    /**
     * The SMB path of a relative path under the prefix ("\"-separated, relative to the share). Re-validates every
     * segment, so a path that did not come from {@link #child} cannot leave the prefix either.
     */
    public static String toShare(String prefix, String relative) {
        StringBuilder out = new StringBuilder();
        for (String part : new String[]{prefix, relative}) {
            if (part == null || part.isEmpty()) continue;
            for (String segment : part.split("/", -1)) {
                if (!isSafeName(segment)) throw new IllegalArgumentException("PATH_SEGMENT_INVALID");
                if (!out.isEmpty()) out.append('\\');
                out.append(segment);
            }
        }
        if (out.length() > MAX_PATH * 2) throw new IllegalArgumentException("PATH_TOO_LONG");
        return out.toString();
    }

    /** The file name part of a relative path. */
    public static String fileName(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    /** Whether the pipeline reads this kind of file; Office lock files ("~$...") never are documents. */
    public static boolean isCandidate(String name) {
        if (name.startsWith("~$")) return false;
        int dot = name.lastIndexOf('.');
        return dot > 0 && SUPPORTED.contains(name.substring(dot).toLowerCase(Locale.ROOT));
    }
}
