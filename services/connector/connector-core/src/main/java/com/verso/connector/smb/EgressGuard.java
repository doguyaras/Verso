package com.verso.connector.smb;

import com.verso.connector.config.ConnectorProperties;
import com.verso.connector.domain.NetworkAllowlist;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.stereotype.Component;

/**
 * The connector's egress check (ADR-0038, security-rules 5 SSRF). The host is resolved once, here, and EVERY address
 * it resolves to must lie in the installation's allowlist of internal networks; the client then connects to that
 * address literally, so a second lookup cannot return a different one (DNS rebinding). SMB has no redirects; DFS
 * referrals, its nearest equivalent, are switched off in the client.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class EgressGuard {

    /** The JDK resolver has no per-call timeout; the lookup runs on its own thread and is abandoned after this. */
    private static final Duration LOOKUP_TIMEOUT = Duration.ofSeconds(5);

    private final NetworkAllowlist allowlist;
    private final ExecutorService lookups = Executors.newVirtualThreadPerTaskExecutor();

    public EgressGuard(ConnectorProperties properties) {
        this.allowlist = properties.allowlist();
    }

    /**
     * The address to connect to.
     *
     * @throws SourceException fatal EGRESS_DENIED (outside the allowlist, or the list is empty) or SOURCE_UNREACHABLE
     *                         (the name does not resolve)
     */
    public InetAddress resolve(String host) {
        if (allowlist.isEmpty()) throw SourceException.fatal("EGRESS_DENIED");
        InetAddress[] addresses = lookup(host);
        for (InetAddress address : addresses) {
            // All of them: a name that also resolves to an outside address could be steered there on another try.
            if (!allowlist.allows(address)) throw SourceException.fatal("EGRESS_DENIED");
        }
        return addresses[0];
    }

    /** Whether an address is inside the private allowlist (the Confluence Data Center client's connect filter). */
    public boolean allows(InetAddress address) {
        return !allowlist.isEmpty() && allowlist.allows(address);
    }

    /**
     * Every address the host resolves to, with a time limit; an IP literal is itself.
     *
     * @throws SourceException fatal SOURCE_UNREACHABLE (no answer) or EGRESS_DENIED (a malformed literal)
     */
    public InetAddress[] lookup(String host) {
        InetAddress[] addresses;
        if (NetworkAllowlist.isLiteral(host)) {
            addresses = new InetAddress[]{literal(host)};
        } else {
            CompletableFuture<InetAddress[]> lookup = CompletableFuture.supplyAsync(() -> {
                try {
                    return InetAddress.getAllByName(host);
                } catch (UnknownHostException e) {
                    throw new IllegalStateException(e);
                }
            }, lookups);
            try {
                addresses = lookup.get(LOOKUP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new SourceException("SOURCE_UNREACHABLE", true, e);
            } catch (ExecutionException | TimeoutException e) {
                lookup.cancel(true);
                throw new SourceException("SOURCE_UNREACHABLE", true, e);
            }
        }
        if (addresses.length == 0) throw SourceException.fatal("SOURCE_UNREACHABLE");
        return addresses;
    }

    /** Whether an IP literal is allowed (admin validation; names are judged at sync time, DNS may change). */
    public boolean allowsLiteral(String host) {
        return !allowlist.isEmpty() && allowlist.allows(literal(host));
    }

    private static InetAddress literal(String host) {
        try {
            return NetworkAllowlist.literal(host);
        } catch (IllegalArgumentException e) {
            throw SourceException.fatal("EGRESS_DENIED");
        }
    }
}
