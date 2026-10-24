package com.verso.connector.sharepoint;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.SharePointSite;
import com.verso.connector.smb.ConnectorCredentials;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.SourceException;
import java.net.URI;
import java.time.Clock;
import java.util.Arrays;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * Opens a SharePoint source for one run (ADR-0045): the egress rule, the credential, the token client and the Graph
 * client. The egress rule is judged again at every run (a row written under other settings, a changed allowlist or AI
 * mode): {@link SharePointEgress#allowsSite} and every resolved address of Graph and Entra public, before any request.
 * The client secret or the private key is read from the {@code SECRET_CONNECTOR_*} file here, held for the run only
 * and never stored, logged or returned.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class SharePointSourceFactory {

    private static final Pattern GUID =
            Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");

    private final SharePointEgress egress;
    private final ConnectorCredentials credentials;
    private final ConnectorProperties properties;
    private final Clock clock;

    public SharePointSourceFactory(SharePointEgress egress, ConnectorCredentials credentials,
                                   ConnectorProperties properties, Clock clock) {
        this.egress = egress;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws SourceException fatal EGRESS_DENIED, SOURCE_UNREACHABLE, CREDENTIAL_MISSING, CREDENTIAL_INVALID or
     *                         CREDENTIAL_EXPIRED
     * @throws IllegalArgumentException a stored site the current rules refuse (CONFIG_INVALID)
     */
    public FileSource open(SharePointRunSettings settings) {
        ConnectorProperties.SharePoint sharepoint = properties.sharepoint();
        SharePointSite site = SharePointSite.parse(settings.siteUrl(), settings.siteId(), false);
        if (!GUID.matcher(settings.tenantId()).matches() || !GUID.matcher(settings.clientId()).matches()) {
            throw new IllegalArgumentException("APP_INVALID");
        }
        if (!egress.allowsSite(site.host())) throw SourceException.fatal("EGRESS_DENIED");
        egress.checkEndpoints();
        EntraTokens.Credential credential = credential(settings);
        GraphTransport transport = GraphTransport.http(sharepoint.connectTimeout(), sharepoint.readTimeout(),
                egress.addressFilter());
        URI tokenEndpoint = EntraTokens.endpoint(egress.authority(), settings.tenantId());
        String scope = egress.graph().getScheme() + "://" + egress.graph().getRawAuthority() + "/.default";
        EntraTokens tokens = new EntraTokens(tokenEndpoint, settings.clientId(), scope, credential, transport, sharepoint,
                clock, duration -> Thread.sleep(duration));
        GraphClient client = new GraphClient(egress, tokens, transport, sharepoint, clock, duration -> Thread.sleep(duration));
        return new ClosingSource(new SharePointFileSource(settings, client, sharepoint.pageSize(),
                properties.maxListedEntries(), properties.maxDepth()), tokens);
    }

    private EntraTokens.Credential credential(SharePointRunSettings settings) {
        char[] secret = credentials.password(settings.credentialRef());
        if (!settings.certificate()) return new EntraTokens.Secret(secret);
        try {
            return new EntraTokens.Assertion(ClientAssertion.parse(secret, clock.instant()),
                    properties.sharepoint().assertionAlgorithm());
        } finally {
            Arrays.fill(secret, '\0');
        }
    }

    /** Zeroes the run's secret and forgets the token when the run ends. */
    private record ClosingSource(SharePointFileSource source, EntraTokens tokens) implements FileSource {
        @Override
        public Listing list(Runnable keepAlive) {
            return source.list(keepAlive);
        }

        @Override
        public byte[] read(String relativePath, long maxBytes) {
            return source.read(relativePath, maxBytes);
        }

        @Override
        public String documentName(com.verso.connector.domain.SyncPlanner.ListedFile file) {
            return source.documentName(file);
        }

        @Override
        public void close() {
            source.close();
            tokens.close();
        }

        @Override
        public String toString() {
            return source.toString();
        }
    }
}
