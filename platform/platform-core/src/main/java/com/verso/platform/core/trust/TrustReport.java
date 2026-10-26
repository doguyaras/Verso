package com.verso.platform.core.trust;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * The facts the modules contribute to the trust report (ADR-0050): configuration and counts, never a value that is a
 * secret and never document, question or answer content. Built per request on one thread, so it is not synchronized.
 */
public final class TrustReport {

    private boolean externalConnectorEgress;
    private final List<String> egressHosts = new ArrayList<>();
    private final Set<String> connectorTypes = new TreeSet<>();
    private boolean cloudConnectorPresent;
    private boolean connectorsUnreadable;
    private Instant lastAuditEventAt;
    private Instant oldestOutboxAt;

    public void externalConnectorEgress(boolean enabled, List<String> allowedHosts) {
        this.externalConnectorEgress = enabled;
        this.egressHosts.clear();
        if (enabled) this.egressHosts.addAll(allowedHosts);
    }

    /** One configured connector; {@code cloud}: its source is on the internet (Confluence Cloud, SharePoint Online). */
    public void connector(String type, boolean cloud) {
        connectorTypes.add(type);
        cloudConnectorPresent |= cloud;
    }

    /** The connector list could not be read: the report must say unknown, not none. */
    public void connectorsUnreadable() {
        this.connectorsUnreadable = true;
    }

    public boolean connectorsKnown() {
        return !connectorsUnreadable;
    }

    public void lastAuditEventAt(Instant at) {
        this.lastAuditEventAt = at;
    }

    /** The creation time of an outbox row still to deliver; the report keeps the oldest of all modules. */
    public void outboxPendingSince(Instant at) {
        if (at != null && (oldestOutboxAt == null || at.isBefore(oldestOutboxAt))) oldestOutboxAt = at;
    }

    public boolean externalConnectorEgress() {
        return externalConnectorEgress;
    }

    public List<String> egressHosts() {
        return List.copyOf(egressHosts);
    }

    public List<String> connectorTypes() {
        return List.copyOf(connectorTypes);
    }

    public boolean cloudConnectorPresent() {
        return cloudConnectorPresent;
    }

    /** Null: no event yet, or the audit log could not be read. */
    public Instant lastAuditEventAt() {
        return lastAuditEventAt;
    }

    /** Null: nothing waits in any outbox. */
    public Instant oldestOutboxAt() {
        return oldestOutboxAt;
    }
}
