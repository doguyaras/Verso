package com.verso.archfixture.tx;

import org.springframework.web.client.RestClient;

/** Violation through the interface annotation. */
public class TransactionalPortImpl implements TransactionalPort {

    @Override
    public void fetch(RestClient client) {
        client.get();
    }
}
