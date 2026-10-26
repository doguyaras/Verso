package com.verso.platform.core.trust;

/**
 * A module's part of the trust report (ADR-0050). Each module states what only it knows (its egress settings, its
 * connector types, its audit and outbox state) without another module reading its tables or classes; the controller
 * that serves GET /v1/admin/trust only collects them. An implementation must not throw for an unavailable resource:
 * it leaves the fact unset, which the report shows as unknown.
 */
public interface TrustContributor {

    void contribute(TrustReport report);
}
