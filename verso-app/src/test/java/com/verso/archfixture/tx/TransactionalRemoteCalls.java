package com.verso.archfixture.tx;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Deliberate violations for TransactionBoundaryRulesTest (test sources only, no stereotype annotation: never a bean).
 * One allowed case as well, so the rule is shown to tell them apart.
 */
public class TransactionalRemoteCalls {

    /** Violation: an HTTP call while the transaction holds a connection (and maybe locks). */
    @Transactional
    public void restCallInsideTransaction(RestClient client) {
        client.get();
    }

    /** Allowed: NOT_SUPPORTED suspends the transaction around the call. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void restCallWithTransactionSuspended(RestClient client) {
        client.get();
    }

    /** Allowed: no transaction at all. */
    public void restCallWithoutTransaction(RestClient client) {
        client.get();
    }
}
