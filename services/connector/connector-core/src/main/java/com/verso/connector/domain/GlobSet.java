package com.verso.connector.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Include or exclude patterns of a connector (ADR-0038), matched against a file's path relative to the prefix,
 * ignoring case like SMB does. A pattern without "/" matches the file name in any folder ("*.pdf"); a pattern with "/"
 * matches the whole relative path ("hr/policies/**"). "*" is any run of characters within one name, "**" any run across
 * folders ("**&#47;" also matches no folder), "?" one character, "{a,b}" either alternative (not nested). Compiled to
 * our own regular expression rather than a file-system glob, so the result does not depend on the host OS.
 */
public final class GlobSet {

    public static final int MAX_PATTERN = 200;
    static final int MAX_STARS = 6;

    private final List<Pattern> byName;
    private final List<Pattern> byPath;

    private GlobSet(List<Pattern> byName, List<Pattern> byPath) {
        this.byName = byName;
        this.byPath = byPath;
    }

    /**
     * @throws IllegalArgumentException with a fixed reason word for an unusable pattern
     */
    public static GlobSet compile(List<String> patterns) {
        List<Pattern> name = new ArrayList<>();
        List<Pattern> path = new ArrayList<>();
        for (String raw : patterns == null ? List.<String>of() : patterns) {
            if (raw == null || raw.isBlank() || raw.length() > MAX_PATTERN) throw new IllegalArgumentException("GLOB_INVALID");
            String glob = raw.strip().replace('\\', '/');
            while (glob.startsWith("/")) glob = glob.substring(1);
            if (glob.isEmpty() || glob.contains("..")) throw new IllegalArgumentException("GLOB_INVALID");
            // Each "*" becomes a ".*"-like group; many of them make a backtracking match polynomial in the path length
            // and could stall the sync thread on a long path (security review B1). Real patterns need a few.
            if (glob.chars().filter(c -> c == '*').count() > MAX_STARS) throw new IllegalArgumentException("GLOB_INVALID");
            Pattern compiled = Pattern.compile(toRegex(glob), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
            (glob.contains("/") ? path : name).add(compiled);
        }
        return new GlobSet(List.copyOf(name), List.copyOf(path));
    }

    public boolean isEmpty() {
        return byName.isEmpty() && byPath.isEmpty();
    }

    /** Whether any pattern matches the relative path ("/"-separated). */
    public boolean matches(String relativePath) {
        String name = SourcePaths.fileName(relativePath);
        for (Pattern p : byName) if (p.matcher(name).matches()) return true;
        for (Pattern p : byPath) if (p.matcher(relativePath).matches()) return true;
        return false;
    }

    static String toRegex(String glob) {
        StringBuilder out = new StringBuilder();
        boolean inBraces = false;
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    if (i + 1 < glob.length() && glob.charAt(i + 1) == '*') {
                        boolean slash = i + 2 < glob.length() && glob.charAt(i + 2) == '/';
                        out.append(slash ? "(?:.*/)?" : ".*");
                        i += slash ? 2 : 1;
                    } else {
                        out.append("[^/]*");
                    }
                }
                case '?' -> out.append("[^/]");
                case '{' -> {
                    if (inBraces) throw new IllegalArgumentException("GLOB_INVALID");
                    inBraces = true;
                    out.append("(?:");
                }
                case '}' -> {
                    if (!inBraces) throw new IllegalArgumentException("GLOB_INVALID");
                    inBraces = false;
                    out.append(')');
                }
                case ',' -> out.append(inBraces ? "|" : ",");
                default -> out.append(Pattern.quote(String.valueOf(c)));
            }
        }
        if (inBraces) throw new IllegalArgumentException("GLOB_INVALID");
        return out.toString();
    }
}
