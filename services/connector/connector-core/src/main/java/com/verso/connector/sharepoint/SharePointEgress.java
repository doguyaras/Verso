package com.verso.connector.sharepoint;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.confluence.ExternalEgressPolicy;
import com.verso.connector.domain.HostAllowlist;
import com.verso.connector.domain.NetworkAllowlist;
import com.verso.connector.smb.EgressGuard;
import com.verso.connector.smb.SourceException;
import java.net.InetAddress;
import java.net.URI;
import java.util.Locale;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.stereotype.Component;

/**
 * The egress rule of the SharePoint connector (ADR-0045; llm-rules 1.1 and 1.7). SharePoint Online is cloud only, so
 * every request goes to the internet and needs, all at once: external connector egress on, the application in cloud
 * mode (strict local mode refuses it at start-up and here), and each host in the installation's allowlist:
 * {@code graph.microsoft.com}, {@code login.microsoftonline.com} and the SharePoint hosts ({@code *.sharepoint.com}),
 * the latter also in {@code verso.connector.sharepoint.download-hosts}. Every resolved address must be public and the
 * connect filter admits public addresses only, so a name steered inside the network cannot make the connector an
 * internal scanner.
 *
 * <p>A test stub (require-https=false) may sit on an IP literal of the private allowlist instead; that path exists only
 * with https turned off, which production refuses together with any non-global endpoint (ConnectorProperties).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SharePointEgress {

    private final ExternalEgressPolicy external;
    private final EgressGuard egress;
    private final ConnectorProperties.SharePoint settings;
    private final HostAllowlist downloadHosts;
    private final URI graph;
    private final URI authority;

    public SharePointEgress(ConnectorProperties properties, ExternalEgressPolicy external, EgressGuard egress) {
        this.external = external;
        this.egress = egress;
        this.settings = properties.sharepoint();
        this.downloadHosts = settings.downloadHostList();
        this.graph = URI.create(settings.graphUrl());
        this.authority = URI.create(settings.authority());
    }

    /** Only a test stub: plain http and endpoints on the private allowlist. */
    public boolean stub() {
        return !settings.requireHttps();
    }

    public URI graph() {
        return graph;
    }

    public URI authority() {
        return authority;
    }

    /**
     * Whether a site on this host may be synced: external egress usable in this mode, the host a SharePoint host of the
     * installation, and the Graph and Entra endpoints allowed too (admin validation and every run).
     */
    public boolean allowsSite(String siteHost) {
        if (!external.enabled() || siteHost == null || NetworkAllowlist.isLiteral(siteHost)) return false;
        return isDownloadHost(siteHost) && endpointAllowed(graph.getHost()) && endpointAllowed(authority.getHost());
    }

    /**
     * Checks the endpoints' addresses before the first request: every address of Graph and Entra must be public (the
     * stub's literal must be in the private allowlist).
     *
     * @throws SourceException fatal EGRESS_DENIED or SOURCE_UNREACHABLE
     */
    public void checkEndpoints() {
        for (String host : new String[]{graph.getHost(), authority.getHost()}) {
            if (!endpointAllowed(host)) throw SourceException.fatal("EGRESS_DENIED");
            if (stub() && NetworkAllowlist.isLiteral(host)) continue;
            InetAddressFilter publicOnly = InetAddressFilter.externalAddresses();
            for (InetAddress address : egress.lookup(host)) {
                if (!publicOnly.matches(address)) throw SourceException.fatal("EGRESS_DENIED");
            }
        }
    }

    /** The connect filter of the run's HTTP client: public addresses only (the stub: the private allowlist). */
    public InetAddressFilter addressFilter() {
        return stub() ? InetAddressFilter.adapt(egress::allows) : InetAddressFilter.externalAddresses();
    }

    /**
     * Whether a file download may follow Graph's redirect to this URL. Only https to a SharePoint host the installation
     * allows; no user info; never another host (the request carries no token either way: the URL is pre-authenticated).
     * The stub may redirect to its own origin.
     */
    public boolean downloadAllowed(URI target) {
        if (target == null || target.getHost() == null || target.getRawUserInfo() != null) return false;
        String host = target.getHost().toLowerCase(Locale.ROOT);
        if (stub() && sameOrigin(target, graph)) return true;
        if (!"https".equalsIgnoreCase(target.getScheme())) return false;
        if (target.getPort() != -1 && target.getPort() != 443) return false;
        return !NetworkAllowlist.isLiteral(host) && isDownloadHost(host) && external.allows(host);
    }

    /** A link Graph returned (next page, delta link): only Graph's own origin and its drive or site paths. */
    public URI graphLink(String link) {
        if (link == null || link.isBlank() || link.length() > 8192) return null;
        URI uri;
        try {
            uri = new URI(link.strip());
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (!sameOrigin(uri, graph) || uri.getRawUserInfo() != null || uri.getRawFragment() != null) return null;
        String path = uri.getRawPath();
        String base = graph.getRawPath() == null ? "" : graph.getRawPath();
        if (path == null || !(path.startsWith(base + "/drives/") || path.startsWith(base + "/sites/"))) return null;
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("/../") || lower.endsWith("/..") || lower.contains("%2e") || lower.contains("%2f")
                || lower.contains("%5c") || lower.contains("\\")) {
            return null;
        }
        return uri;
    }

    private boolean isDownloadHost(String host) {
        return downloadHosts.allows(host) && external.allows(host);
    }

    private boolean endpointAllowed(String host) {
        if (!external.enabled() || host == null) return false;
        if (NetworkAllowlist.isLiteral(host)) {
            if (!stub()) return false;
            try {
                return egress.allowsLiteral(host);
            } catch (SourceException e) {
                return false;
            }
        }
        return external.allows(host);
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme() != null && a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost() != null
                && a.getHost().equalsIgnoreCase(b.getHost()) && port(a) == port(b);
    }

    private static int port(URI uri) {
        if (uri.getPort() != -1) return uri.getPort();
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }
}
