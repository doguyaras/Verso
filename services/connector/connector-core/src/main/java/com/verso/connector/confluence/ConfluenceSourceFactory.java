package com.verso.connector.confluence;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.ConfluenceUrl;
import com.verso.connector.domain.NetworkAllowlist;
import com.verso.connector.smb.ConnectorCredentials;
import com.verso.connector.smb.EgressGuard;
import com.verso.connector.smb.FileSource;
import com.verso.connector.smb.SourceException;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Arrays;
import java.util.Base64;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.InetAddressFilter;
import org.springframework.stereotype.Component;

/**
 * Opens a Confluence source for one run (ADR-0044): the egress rule of the deployment, the credential, the client.
 *
 * <ul>
 *   <li><b>Data Center:</b> the site's name must resolve, with every address, into the private allowlist
 *       ({@code verso.connector.allowed-networks}, the same as SMB); the HTTP client's connect filter re-checks the
 *       address it actually connects to, so a second DNS answer cannot leave the allowlist (rebinding).</li>
 *   <li><b>Cloud:</b> only when {@link ExternalEgressPolicy} allows the host (enabled, allowlisted, not strict local
 *       mode); every resolved address must be a public one, and the connect filter allows public addresses only, so a
 *       name pointing inside the network cannot turn the connector into an internal scanner.</li>
 *   <li>Credentials: Data Center a personal access token as Bearer; Cloud the account e-mail and an API token as Basic.
 *       Read from the {@code SECRET_CONNECTOR_*} file at open, never stored, logged or returned.</li>
 * </ul>
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ConfluenceSourceFactory {

    private final EgressGuard egress;
    private final ExternalEgressPolicy external;
    private final ConnectorCredentials credentials;
    private final ConnectorProperties properties;
    private final Clock clock;

    public ConfluenceSourceFactory(EgressGuard egress, ExternalEgressPolicy external, ConnectorCredentials credentials,
                                   ConnectorProperties properties, Clock clock) {
        this.egress = egress;
        this.external = external;
        this.credentials = credentials;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * @throws SourceException fatal EGRESS_DENIED, SOURCE_UNREACHABLE or CREDENTIAL_MISSING
     * @throws IllegalArgumentException a stored URL the current rules refuse (CONFIG_INVALID)
     */
    public FileSource open(ConfluenceRunSettings settings) {
        ConnectorProperties.Confluence confluence = properties.confluence();
        ConfluenceUrl site = ConfluenceUrl.parse(settings.baseUrl(), !confluence.requireHttps());
        InetAddressFilter addresses = settings.cloud() ? cloud(site) : dataCenter(site);
        String authorization = authorization(settings);
        ConfluenceClient client = new ConfluenceClient(site, authorization,
                ConfluenceTransport.http(confluence.connectTimeout(), confluence.readTimeout(), addresses), confluence,
                clock, duration -> Thread.sleep(duration));
        return new ConfluenceFileSource(settings, client, confluence.cursorOverlap(), properties.maxListedEntries(), clock);
    }

    private InetAddressFilter dataCenter(ConfluenceUrl site) {
        egress.resolve(site.host());
        return InetAddressFilter.adapt(egress::allows);
    }

    private InetAddressFilter cloud(ConfluenceUrl site) {
        if (NetworkAllowlist.isLiteral(site.host()) || !external.allows(site.host())) {
            throw SourceException.fatal("EGRESS_DENIED");
        }
        InetAddressFilter publicOnly = InetAddressFilter.externalAddresses();
        for (InetAddress address : egress.lookup(site.host())) {
            if (!publicOnly.matches(address)) throw SourceException.fatal("EGRESS_DENIED");
        }
        return publicOnly;
    }

    private String authorization(ConfluenceRunSettings settings) {
        char[] secret = credentials.password(settings.credentialRef());
        try {
            if (!settings.cloud()) return "Bearer " + new String(secret);
            if (settings.username() == null || settings.username().isBlank()) {
                throw SourceException.fatal("CREDENTIAL_MISSING");
            }
            char[] pair = new char[settings.username().length() + 1 + secret.length];
            settings.username().getChars(0, settings.username().length(), pair, 0);
            pair[settings.username().length()] = ':';
            System.arraycopy(secret, 0, pair, settings.username().length() + 1, secret.length);
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(CharBuffer.wrap(pair));
            Arrays.fill(pair, '\0');
            byte[] raw = new byte[bytes.remaining()];
            bytes.get(raw);
            String header = "Basic " + Base64.getEncoder().encodeToString(raw);
            Arrays.fill(raw, (byte) 0);
            return header;
        } finally {
            Arrays.fill(secret, '\0');
        }
    }
}
