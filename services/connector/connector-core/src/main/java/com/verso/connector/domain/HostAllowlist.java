package com.verso.connector.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The internet hosts a connector may reach when external egress is on (ADR-0044), e.g. {@code *.atlassian.net}. An
 * entry is an exact host name or one leading {@code *.} label over a domain of at least two labels: {@code *.net} or
 * {@code *} would open the internet and are refused, as are IP literals (a public address says nothing about whose
 * it is). Matching is on the name the admin configured; the address it resolves to is checked separately (public only).
 */
public final class HostAllowlist {

    private static final Pattern LABEL = Pattern.compile("[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?");

    private final List<String> exact;
    private final List<String> suffixes;

    private HostAllowlist(List<String> exact, List<String> suffixes) {
        this.exact = List.copyOf(exact);
        this.suffixes = List.copyOf(suffixes);
    }

    /**
     * @throws IllegalArgumentException for an IP literal, a bare or broad wildcard, or a malformed name
     */
    public static HostAllowlist parse(String spec) {
        List<String> exact = new ArrayList<>();
        List<String> suffixes = new ArrayList<>();
        if (spec == null || spec.isBlank()) return new HostAllowlist(exact, suffixes);
        for (String raw : spec.split(",")) {
            String entry = raw.strip().toLowerCase(Locale.ROOT);
            if (entry.isEmpty()) continue;
            boolean wildcard = entry.startsWith("*.");
            String name = wildcard ? entry.substring(2) : entry;
            if (!isHostName(name) || name.split("\\.").length < 2) {
                throw new IllegalArgumentException("verso.connector.external-egress.allowed-hosts: an entry is a host "
                        + "name of at least two labels, optionally with one leading '*.'; no IP address, no bare '*'");
            }
            if (wildcard) suffixes.add("." + name);
            else exact.add(name);
        }
        return new HostAllowlist(exact, suffixes);
    }

    public boolean isEmpty() {
        return exact.isEmpty() && suffixes.isEmpty();
    }

    /** Whether the host name (not an IP literal) is allowed. */
    public boolean allows(String host) {
        if (host == null) return false;
        String name = host.toLowerCase(Locale.ROOT);
        if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
        if (!isHostName(name)) return false;
        if (exact.contains(name)) return true;
        for (String suffix : suffixes) {
            // "*.atlassian.net" allows "x.atlassian.net" but not "atlassian.net" itself nor "xatlassian.net".
            if (name.endsWith(suffix) && name.length() > suffix.length()) return true;
        }
        return false;
    }

    /** A DNS name of letters, digits and hyphens; an IPv4 literal (all-numeric last label) is not one. */
    static boolean isHostName(String name) {
        if (name.isEmpty() || name.length() > 253) return false;
        String[] labels = name.split("\\.", -1);
        for (String label : labels) {
            if (!LABEL.matcher(label).matches()) return false;
        }
        return !labels[labels.length - 1].chars().allMatch(Character::isDigit);
    }
}
