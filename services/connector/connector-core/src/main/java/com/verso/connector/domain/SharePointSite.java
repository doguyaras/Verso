package com.verso.connector.domain;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A SharePoint Online site or a OneDrive (ADR-0045), given by its URL or its Graph site id, strictly parsed: the value
 * ends up in a Graph request path, so only a host name and plain path segments pass. The site's host is also what the
 * egress rule judges (it must be a SharePoint host the installation allows).
 *
 * @param host   the site's host, lower case ({@code contoso.sharepoint.com}, {@code contoso-my.sharepoint.com})
 * @param path   the server-relative path ({@code /sites/hr}), "" for the root site; null when only the id was given
 * @param siteId the Graph site id ({@code host,site-guid,web-guid}); null when only the URL was given
 */
public record SharePointSite(String host, String path, String siteId) {

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,128}");
    private static final Pattern SITE_ID = Pattern.compile("([A-Za-z0-9.-]{1,253}),[0-9A-Fa-f-]{36},[0-9A-Fa-f-]{36}");
    private static final int MAX_SEGMENTS = 10;

    /**
     * @param allowHttp only a test stub (verso.connector.sharepoint.require-https=false)
     * @throws IllegalArgumentException with a fixed reason word, never echoing the input
     */
    public static SharePointSite parse(String siteUrl, String siteId, boolean allowHttp) {
        boolean hasUrl = siteUrl != null && !siteUrl.isBlank();
        boolean hasId = siteId != null && !siteId.isBlank();
        if (!hasUrl && !hasId) throw new IllegalArgumentException("SITE_MISSING");
        String urlHost = null;
        String path = null;
        if (hasUrl) {
            URI uri;
            try {
                uri = new URI(siteUrl.strip());
            } catch (URISyntaxException e) {
                throw new IllegalArgumentException("URL_INVALID");
            }
            String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
            if (!scheme.equals("https") && !(allowHttp && scheme.equals("http"))) throw new IllegalArgumentException("URL_NOT_HTTPS");
            if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || uri.getHost() == null || (uri.getPort() != -1 && !allowHttp)) {
                throw new IllegalArgumentException("URL_INVALID");
            }
            urlHost = uri.getHost().toLowerCase(Locale.ROOT);
            if (!allowHttp && !HostAllowlist.isHostName(urlHost)) throw new IllegalArgumentException("URL_INVALID");
            path = normalizePath(uri.getRawPath());
        }
        String idHost = null;
        String id = null;
        if (hasId) {
            Matcher m = SITE_ID.matcher(siteId.strip());
            if (!m.matches()) throw new IllegalArgumentException("SITE_ID_INVALID");
            idHost = m.group(1).toLowerCase(Locale.ROOT);
            if (!HostAllowlist.isHostName(idHost)) throw new IllegalArgumentException("SITE_ID_INVALID");
            id = idHost + siteId.strip().substring(m.group(1).length()).toLowerCase(Locale.ROOT);
        }
        if (urlHost != null && idHost != null && !urlHost.equals(idHost)) throw new IllegalArgumentException("SITE_MISMATCH");
        return new SharePointSite(urlHost != null ? urlHost : idHost, path, id);
    }

    private static String normalizePath(String raw) {
        if (raw == null || raw.isEmpty() || raw.equals("/")) return "";
        String trimmed = raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        if (!trimmed.startsWith("/")) throw new IllegalArgumentException("URL_INVALID");
        String[] segments = trimmed.substring(1).split("/", -1);
        if (segments.length > MAX_SEGMENTS) throw new IllegalArgumentException("URL_INVALID");
        for (String segment : segments) {
            if (!SEGMENT.matcher(segment).matches() || segment.equals(".") || segment.equals("..")) {
                throw new IllegalArgumentException("URL_INVALID");
            }
        }
        return trimmed;
    }

    /** The normalised URL ({@code https://host/path}) or null when only the id was given. */
    public String url() {
        return path == null ? null : "https://" + host + path;
    }

    /** The Graph path that addresses the site: by id, or by host and server-relative path. */
    public String graphPath() {
        if (siteId != null) return "/sites/" + siteId;
        return path.isEmpty() ? "/sites/" + host : "/sites/" + host + ":" + path;
    }

    /** The host only (security-rules 4: never the site path in a log line). */
    @Override
    public String toString() {
        return "SharePointSite[host]";
    }
}
