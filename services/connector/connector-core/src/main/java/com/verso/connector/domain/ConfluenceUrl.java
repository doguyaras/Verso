package com.verso.connector.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A Confluence site's base URL (ADR-0044), parsed strictly: {@code https://host[:port][/context]}. No user info (a
 * credential in a URL would reach logs and proxies), no query, no fragment, no "." or ".." segment. Every request the
 * connector makes is this origin plus a path the connector builds or a server link that stays under the context
 * ({@link #resolve}); anything else is refused, so a server cannot steer the client to another host or path.
 *
 * @param contextPath "" or "/wiki" (no trailing slash)
 */
public record ConfluenceUrl(String scheme, String host, int port, String contextPath) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,100}");
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9.-]{1,253}");
    /** Server links the client follows: REST paths and attachment downloads, nothing else. */
    private static final Pattern LINK = Pattern.compile("/(rest/api/|download/attachments/)[A-Za-z0-9._~%/?&=+,:;@*!()$'-]*");

    /**
     * @param allowHttp only a test stub sets it (verso.connector.confluence.require-https=false)
     * @throws IllegalArgumentException with a fixed reason
     */
    public static ConfluenceUrl parse(String raw, boolean allowHttp) {
        if (raw == null || raw.isBlank() || raw.length() > 2048) throw new IllegalArgumentException("URL_INVALID");
        String text = raw.strip();
        while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
        URI uri;
        try {
            uri = new URI(text);
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("URL_INVALID");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) throw new IllegalArgumentException("URL_NOT_HTTPS");
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null || uri.isOpaque()) {
            throw new IllegalArgumentException("URL_INVALID");
        }
        String host = uri.getHost();
        if (host == null || !HOST.matcher(host).matches() || host.startsWith(".") || host.endsWith(".")
                || host.contains("..")) {
            throw new IllegalArgumentException("URL_INVALID");
        }
        int port = uri.getPort();
        if (port == 0 || port > 65535) throw new IllegalArgumentException("URL_INVALID");
        if (port < 0) port = scheme.equals("https") ? 443 : 80;
        String path = uri.getRawPath() == null ? "" : uri.getRawPath();
        if (!path.isEmpty()) {
            for (String segment : path.substring(1).split("/", -1)) {
                if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                    throw new IllegalArgumentException("URL_INVALID");
                }
            }
        }
        return new ConfluenceUrl(scheme, host.toLowerCase(Locale.ROOT), port, path);
    }

    /** The canonical form stored and shown: scheme, host, the port when it is not the default, the context. */
    public String text() {
        boolean defaultPort = (scheme.equals("https") && port == 443) || (scheme.equals("http") && port == 80);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + contextPath;
    }

    /** An absolute URI for a path under the context that the connector built itself (already encoded). */
    public URI api(String pathAndQuery) {
        if (!pathAndQuery.startsWith("/rest/api/")) throw new IllegalArgumentException("not a REST path");
        return URI.create(text() + pathAndQuery);
    }

    /**
     * A link the server returned ({@code _links.next}, an attachment's {@code _links.download}), relative to the base:
     * only REST and attachment-download paths, no "..", no scheme or host of its own; else null and the caller stops.
     */
    public URI resolve(String link) {
        if (link == null || link.length() > 4096 || !LINK.matcher(link).matches()) return null;
        String path = link.contains("?") ? link.substring(0, link.indexOf('?')) : link;
        for (String segment : path.split("/")) {
            if (segment.equals("..") || segment.equals(".")) return null;
        }
        // An encoded dot, slash or backslash in the path could become ".." after the server decodes it.
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("%2e") || lower.contains("%2f") || lower.contains("%5c")) return null;
        try {
            return new URI(text() + link);
        } catch (URISyntaxException e) {
            return null;
        }
    }

    /** Whether a redirect target stays on this origin and under the context (the only redirects followed). */
    public boolean sameOrigin(URI target) {
        if (target == null || target.getHost() == null || target.getRawUserInfo() != null) return false;
        int targetPort = target.getPort() < 0 ? ("https".equalsIgnoreCase(target.getScheme()) ? 443 : 80) : target.getPort();
        String path = target.getRawPath() == null ? "" : target.getRawPath();
        return scheme.equalsIgnoreCase(target.getScheme()) && host.equalsIgnoreCase(target.getHost())
                && port == targetPort && (contextPath.isEmpty() || path.equals(contextPath) || path.startsWith(contextPath + "/"))
                && !path.contains("/../");
    }

    /** Never the host in logs: the deployment and ids are enough (security-rules 4). */
    @Override
    public String toString() {
        return "ConfluenceUrl[scheme=" + scheme + "]";
    }
}
