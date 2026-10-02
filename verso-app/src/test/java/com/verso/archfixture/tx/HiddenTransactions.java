package com.verso.archfixture.tx;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

/**
 * Transactions the first rule version missed (phase 2 reviews A2, C3): composed annotation, non-public method under a
 * class-level annotation, a private helper doing the call, and a nested RestClient type.
 */
@Transactional
public class HiddenTransactions {

    @WriteTx
    public void composedAnnotation(RestClient client) {
        client.get();
    }

    void packagePrivateUnderClassLevel(RestClient client) {
        client.get();
    }

    protected void viaPrivateHelper(RestClient client) {
        helper(client);
    }

    private void helper(RestClient client) {
        client.get().retrieve();
    }

    /** Allowed: static methods are never proxied. */
    static void staticCall(RestClient client) {
        client.get();
    }
}
